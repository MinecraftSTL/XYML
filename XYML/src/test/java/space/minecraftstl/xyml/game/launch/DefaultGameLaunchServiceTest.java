/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package space.minecraftstl.xyml.game.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.presentation.TaskSnapshot;
import space.minecraftstl.xyml.task.presentation.TaskStatus;
import space.minecraftstl.xyml.util.platform.ManagedProcess;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies launch preparation single-flight, terminal cleanup, and process ownership boundaries.
@NotNullByDefault
final class DefaultGameLaunchServiceTest {
    /// Two simultaneous launch callers invoke the task factory once while the winning task remains in preparation.
    @Test
    @Timeout(10)
    void concurrentLaunchesInvokeFactoryOnce() throws Exception {
        AtomicInteger factoryCalls = new AtomicInteger();
        CountDownLatch taskEntered = new CountDownLatch(1);
        CountDownLatch taskRelease = new CountDownLatch(1);
        ManagedProcess process = managedProcess(new RecordingProcess());
        BlockingLaunchTask task = new BlockingLaunchTask(process, taskEntered, taskRelease);
        task.setName("Preparing concurrent launch");
        DefaultGameLaunchService service = service(request -> {
            factoryCalls.incrementAndGet();
            return task;
        });
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier startBarrier = new CyclicBarrier(2);
            Future<Optional<LaunchSession>> first = callers.submit(() -> launchAfterBarrier(service, startBarrier));
            Future<Optional<LaunchSession>> second = callers.submit(() -> launchAfterBarrier(service, startBarrier));

            Optional<LaunchSession> firstResult = first.get(5, TimeUnit.SECONDS);
            Optional<LaunchSession> secondResult = second.get(5, TimeUnit.SECONDS);
            assertTrue(firstResult.isPresent() ^ secondResult.isPresent());
            assertTrue(taskEntered.await(5, TimeUnit.SECONDS));
            assertEquals(1, factoryCalls.get());

            LaunchSession winningSession = firstResult.isPresent()
                    ? firstResult.orElseThrow()
                    : secondResult.orElseThrow();
            assertEquals("Test launch", winningSession.snapshot().title());
            assertEquals("Preparing concurrent launch", winningSession.snapshot().phase());
            assertEquals(TaskStatus.RUNNING, winningSession.snapshot().status());
            assertTrue(winningSession.snapshot().progress().isEmpty());
            taskRelease.countDown();
            assertSame(process, winningSession.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(LaunchStatus.PROCESS_CREATED, winningSession.status());
            assertTrue(service.activePreparation().isEmpty());
        } finally {
            taskRelease.countDown();
            service.close();
            callers.shutdownNow();
        }
    }

    /// A preparation failure clears the slot and preserves its cause so a later request can start normally.
    @Test
    @Timeout(10)
    void failureClearsPreparationSlot() throws Exception {
        IOException failure = new IOException("launch preparation failed");
        RecordingProcess rawProcess = new RecordingProcess();
        ManagedProcess process = managedProcess(rawProcess);
        AtomicInteger factoryCalls = new AtomicInteger();
        DefaultGameLaunchService service = service(request ->
                factoryCalls.incrementAndGet() == 1
                        ? new FailingLaunchTask(failure)
                        : new CompletedLaunchTask(process));
        try {
            LaunchSession failedSession = service.launch(request("failed"));
            CompletionException completionFailure = assertThrows(
                    CompletionException.class,
                    () -> failedSession.completion().toCompletableFuture().join());
            assertSame(failure, completionFailure.getCause());
            assertEquals(LaunchStatus.FAILED, failedSession.status());
            assertSame(failure, failedSession.failure().orElseThrow());
            assertTrue(service.activePreparation().isEmpty());

            LaunchSession successfulSession = service.launch(request("retry"));
            assertSame(process, successfulSession.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(2, factoryCalls.get());
        } finally {
            service.close();
        }
        assertEquals(0, rawProcess.destroyCalls());
    }

    /// An asynchronous task error is preserved as the failed session cause and releases the preparation slot.
    @Test
    @Timeout(10)
    void taskErrorIsPreservedAsFailure() {
        AssertionError failure = new AssertionError("launch task error");
        DefaultGameLaunchService service = service(request -> new ErrorLaunchTask(failure));
        try {
            LaunchSession session = service.launch(request("task-error"));
            CompletionException completionFailure = assertThrows(
                    CompletionException.class,
                    () -> session.completion().toCompletableFuture().join());
            assertSame(failure, completionFailure.getCause());
            assertSame(failure, session.failure().orElseThrow());
            assertEquals(LaunchStatus.FAILED, session.status());
            assertEquals(TaskStatus.FAILED, session.snapshot().status());
            assertTrue(service.activePreparation().isEmpty());
        } finally {
            service.close();
        }
    }

    /// Failure-detail formatting cannot strand the service when a custom throwable rejects stack-trace rendering.
    @Test
    @Timeout(10)
    void stackTraceFormattingFailureStillPublishesTerminalStateAndAllowsRetry() throws Exception {
        AssertionError formattingFailure = new AssertionError("test stack-trace formatting failure");
        StackTraceThrowingError launchFailure = new StackTraceThrowingError(formattingFailure);
        AtomicInteger factoryCalls = new AtomicInteger();
        ManagedProcess retryProcess = managedProcess(new RecordingProcess());
        DefaultGameLaunchService service = service(request -> factoryCalls.incrementAndGet() == 1
                ? new ErrorLaunchTask(launchFailure)
                : new CompletedLaunchTask(retryProcess));
        try {
            LaunchSession failedSession = service.launch(request("trace-format-failure"));
            CompletionException completionFailure = assertThrows(
                    CompletionException.class,
                    () -> failedSession.completion().toCompletableFuture().join());
            assertSame(launchFailure, completionFailure.getCause());
            assertSame(launchFailure, failedSession.failure().orElseThrow());
            assertEquals(LaunchStatus.FAILED, failedSession.status());
            assertEquals(TaskStatus.FAILED, failedSession.snapshot().status());
            assertEquals("", failedSession.snapshot().details());
            assertTrue(service.activePreparation().isEmpty());

            LaunchSession retrySession = service.launch(request("trace-format-retry"));
            assertSame(retryProcess, retrySession.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(LaunchStatus.PROCESS_CREATED, retrySession.status());
            assertEquals(2, factoryCalls.get());
        } finally {
            service.close();
        }
    }

    /// Accepted cancellation does not mask a later real exception or error from the running preparation task.
    @Test
    @Timeout(10)
    void cancellationDoesNotMaskRealTaskFailure() throws Exception {
        verifyCancellationThenFailure(new IOException("failure after cancellation"));
        verifyCancellationThenFailure(new AssertionError("error after cancellation"));
    }

    /// Closing the service cancels active preparation, clears the slot, and rejects all later launch requests.
    @Test
    @Timeout(10)
    void closeCancelsOnlyActivePreparation() throws Exception {
        CountDownLatch taskEntered = new CountDownLatch(1);
        CountDownLatch taskRelease = new CountDownLatch(1);
        RecordingProcess rawProcess = new RecordingProcess();
        BlockingLaunchTask task = new BlockingLaunchTask(
                managedProcess(rawProcess),
                taskEntered,
                taskRelease);
        DefaultGameLaunchService service = service(request -> task);

        LaunchSession session = service.launch(request("cancelled"));
        assertTrue(taskEntered.await(5, TimeUnit.SECONDS));
        service.close();

        CompletionException cancellation = assertThrows(
                CompletionException.class,
                () -> session.completion().toCompletableFuture().join());
        assertTrue(cancellation.getCause() instanceof CancellationException);
        assertEquals(LaunchStatus.CANCELLED, session.status());
        assertTrue(service.activePreparation().isEmpty());
        assertEquals(0, rawProcess.destroyCalls());
        assertThrows(IllegalStateException.class, () -> service.launch(request("after-close")));
        taskRelease.countDown();
    }

    /// Closing after successful preparation does not stop the process that has crossed the ownership boundary.
    @Test
    @Timeout(10)
    void closeDoesNotStopCreatedProcess() throws Exception {
        RecordingProcess rawProcess = new RecordingProcess();
        ManagedProcess process = managedProcess(rawProcess);
        DefaultGameLaunchService service = service(request -> new CompletedLaunchTask(process));

        LaunchSession session = service.launch(request("created"));
        assertSame(process, session.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertFalse(process.getCommands().isEmpty());
        service.close();

        assertEquals(LaunchStatus.PROCESS_CREATED, session.status());
        assertSame(process, session.createdProcess().orElseThrow());
        assertEquals(0, rawProcess.destroyCalls());
    }

    /// Runtime status failures and presentation errors cannot retain the service's single-flight slot.
    @Test
    @Timeout(10)
    void observerFailuresDoNotStrandPreparationSlot() throws Exception {
        CountDownLatch taskEntered = new CountDownLatch(1);
        CountDownLatch taskRelease = new CountDownLatch(1);
        ManagedProcess firstProcess = managedProcess(new RecordingProcess());
        ManagedProcess secondProcess = managedProcess(new RecordingProcess());
        AtomicInteger factoryCalls = new AtomicInteger();
        DefaultGameLaunchService service = service(request ->
                factoryCalls.incrementAndGet() == 1
                        ? new BlockingLaunchTask(firstProcess, taskEntered, taskRelease)
                        : new CompletedLaunchTask(secondProcess));
        try {
            LaunchSession firstSession = service.launch(request("observer-failure"));
            firstSession.statusProperty().subscribe(change -> {
                throw new IllegalStateException("status observer failed");
            });
            firstSession.subscribe(change -> {
                if (change.currentValue().status().isTerminal()) {
                    throw new AssertionError("presentation observer failed");
                }
            });
            assertTrue(taskEntered.await(5, TimeUnit.SECONDS));

            taskRelease.countDown();
            assertSame(firstProcess, firstSession.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertTrue(service.activePreparation().isEmpty());

            LaunchSession secondSession = service.launch(request("after-observer-failure"));
            assertSame(secondProcess, secondSession.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(2, factoryCalls.get());
        } finally {
            taskRelease.countDown();
            service.close();
        }
    }

    /// Cancelling while the factory blocks completes immediately and prevents its late task from starting.
    @Test
    @Timeout(10)
    void cancellationDiscardsLateFactoryResult() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch factoryRelease = new CountDownLatch(1);
        CountDownLatch factoryReturned = new CountDownLatch(1);
        AtomicInteger taskExecutions = new AtomicInteger();
        ExecutorService preparationExecutor = Executors.newSingleThreadExecutor();
        DefaultGameLaunchService service = service(request -> {
            factoryEntered.countDown();
            awaitUninterruptibly(factoryRelease);
            factoryReturned.countDown();
            return new CountingLaunchTask(
                    managedProcess(new RecordingProcess()),
                    taskExecutions);
        }, preparationExecutor);
        try {
            LaunchSession session = service.launch(request("blocked-factory"));
            assertTrue(factoryEntered.await(5, TimeUnit.SECONDS));

            service.close();
            CompletionException cancellation = assertThrows(
                    CompletionException.class,
                    () -> session.completion().toCompletableFuture().join());
            assertTrue(cancellation.getCause() instanceof CancellationException);
            assertEquals(LaunchStatus.CANCELLED, session.status());
            assertTrue(service.activePreparation().isEmpty());

            factoryRelease.countDown();
            assertTrue(factoryReturned.await(5, TimeUnit.SECONDS));
            assertEquals(0, taskExecutions.get());
        } finally {
            factoryRelease.countDown();
            service.close();
            preparationExecutor.shutdownNow();
        }
    }

    /// A rejected preparation submission becomes a failed session without invoking the factory or retaining the slot.
    @Test
    @Timeout(10)
    void rejectedPreparationSubmissionFailsSession() {
        AtomicInteger factoryCalls = new AtomicInteger();
        RejectedExecutionException rejection = new RejectedExecutionException("test executor rejected launch");
        Executor rejectingExecutor = command -> {
            throw rejection;
        };
        DefaultGameLaunchService service = service(request -> {
            factoryCalls.incrementAndGet();
            return new CompletedLaunchTask(managedProcess(new RecordingProcess()));
        }, rejectingExecutor);
        try {
            LaunchSession session = service.launch(request("rejected"));
            CompletionException completionFailure = assertThrows(
                    CompletionException.class,
                    () -> session.completion().toCompletableFuture().join());
            assertSame(rejection, completionFailure.getCause());
            assertSame(rejection, session.failure().orElseThrow());
            assertEquals(LaunchStatus.FAILED, session.status());
            assertEquals(TaskStatus.FAILED, session.snapshot().status());
            assertEquals(0, factoryCalls.get());
            assertTrue(service.activePreparation().isEmpty());
        } finally {
            service.close();
        }
    }

    /// A scheduling error remains primary while later observer failures are retained in severity order.
    @Test
    @Timeout(10)
    void schedulingErrorRemainsPrimaryAcrossTerminalObserverErrors() throws Exception {
        AssertionError schedulingError = new AssertionError("test scheduling error");
        IllegalStateException statusFailure = new IllegalStateException("status observer failure");
        AssertionError presentationFailure = new AssertionError("presentation observer failure");
        CountDownLatch submissionEntered = new CountDownLatch(1);
        CountDownLatch submissionRelease = new CountDownLatch(1);
        Executor failingExecutor = command -> {
            submissionEntered.countDown();
            awaitUninterruptibly(submissionRelease);
            throw schedulingError;
        };
        DefaultGameLaunchService service = service(
                request -> new CompletedLaunchTask(managedProcess(new RecordingProcess())),
                failingExecutor);
        ExecutorService launchCaller = Executors.newSingleThreadExecutor();
        try {
            Future<@Nullable Throwable> launchFailure = launchCaller.submit(() -> {
                try {
                    service.launch(request("scheduling-error"));
                    return null;
                } catch (RuntimeException | Error failure) {
                    return failure;
                }
            });
            assertTrue(submissionEntered.await(5, TimeUnit.SECONDS));

            LaunchSession session = service.activePreparation().orElseThrow();
            session.statusProperty().subscribe(change -> {
                throw statusFailure;
            });
            session.subscribe(change -> {
                throw presentationFailure;
            });
            submissionRelease.countDown();

            assertSame(schedulingError, launchFailure.get(5, TimeUnit.SECONDS));
            assertEquals(1, schedulingError.getSuppressed().length);
            assertSame(presentationFailure, schedulingError.getSuppressed()[0]);
            assertEquals(1, presentationFailure.getSuppressed().length);
            assertSame(statusFailure, presentationFailure.getSuppressed()[0]);
            assertEquals(LaunchStatus.FAILED, session.status());
            assertTrue(service.activePreparation().isEmpty());
        } finally {
            submissionRelease.countDown();
            service.close();
            launchCaller.shutdownNow();
        }
    }

    /// Synchronous completion callbacks see coherent terminal launch, property, and task-presentation states.
    @Test
    @Timeout(10)
    void completionCallbacksObserveAllTerminalSurfaces() throws Exception {
        verifySuccessfulCompletionObservation();
        verifyFailedCompletionObservation();
        verifyCancelledCompletionObservation();
    }

    /// Once cancellation is accepted, later adapter progress cannot make the session cancelable or nonterminal again.
    @Test
    @Timeout(10)
    void acceptedCancellationCannotBeReopenedByAdapterUpdates() throws Exception {
        CountDownLatch taskEntered = new CountDownLatch(1);
        CountDownLatch taskRelease = new CountDownLatch(1);
        CountDownLatch runningNotificationEntered = new CountDownLatch(1);
        CountDownLatch runningNotificationRelease = new CountDownLatch(1);
        DeferredCancellationTask task = new DeferredCancellationTask(
                managedProcess(new RecordingProcess()),
                taskEntered,
                taskRelease);
        DefaultGameLaunchService service = service(request -> task);
        try {
            LaunchSession session = service.launch(request("cancelable-overlay"));
            assertTrue(taskEntered.await(5, TimeUnit.SECONDS));
            assertTrue(session.snapshot().cancelable());

            assertTrue(session.cancel());
            assertEquals(LaunchStatus.PREPARING, session.status());
            assertFalse(session.snapshot().cancelable());

            task.reportProgress(0.5);
            assertFalse(session.snapshot().cancelable());
            assertEquals(0.5, session.snapshot().progress().orElseThrow());

            session.subscribe(change -> {
                @Nullable TaskSnapshot current = change.currentValue();
                if (current != null
                        && current.status() == TaskStatus.RUNNING
                        && current.progress().isPresent()
                        && current.progress().getAsDouble() == 0.75) {
                    runningNotificationEntered.countDown();
                    awaitUninterruptibly(runningNotificationRelease);
                }
            });
            Thread lateAdapterUpdate = new Thread(
                    () -> task.reportProgress(0.75),
                    "LateLaunchPresentationUpdate");
            lateAdapterUpdate.start();
            assertTrue(runningNotificationEntered.await(5, TimeUnit.SECONDS));

            taskRelease.countDown();
            assertThrows(
                    CompletionException.class,
                    () -> session.completion().toCompletableFuture().join());
            assertEquals(LaunchStatus.CANCELLED, session.status());
            assertEquals(TaskStatus.CANCELLED, session.snapshot().status());

            runningNotificationRelease.countDown();
            lateAdapterUpdate.join(5000);
            assertFalse(lateAdapterUpdate.isAlive());
            assertEquals(TaskStatus.CANCELLED, session.snapshot().status());
            assertFalse(session.snapshot().cancelable());
        } finally {
            taskRelease.countDown();
            runningNotificationRelease.countDown();
            service.close();
        }
    }

    /// Verifies success state visible from a completion callback registered before queued startup.
    private static void verifySuccessfulCompletionObservation() throws Exception {
        QueuedExecutor preparationExecutor = new QueuedExecutor();
        ManagedProcess process = managedProcess(new RecordingProcess());
        DefaultGameLaunchService service = service(
                request -> new CompletedLaunchTask(process),
                preparationExecutor);
        try {
            LaunchSession session = service.launch(request("completion-success"));
            CompletableFuture<CompletionObservation> observation = observeCompletion(session);
            preparationExecutor.runPending();
            assertSame(process, session.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertCompletionObservation(
                    observation,
                    LaunchStatus.PROCESS_CREATED,
                    TaskStatus.SUCCEEDED);
        } finally {
            service.close();
        }
    }

    /// Verifies failure state visible from a completion callback registered before queued startup.
    private static void verifyFailedCompletionObservation() {
        QueuedExecutor preparationExecutor = new QueuedExecutor();
        IOException failure = new IOException("completion observation failure");
        DefaultGameLaunchService service = service(
                request -> new FailingLaunchTask(failure),
                preparationExecutor);
        try {
            LaunchSession session = service.launch(request("completion-failure"));
            CompletableFuture<CompletionObservation> observation = observeCompletion(session);
            preparationExecutor.runPending();
            assertThrows(
                    CompletionException.class,
                    () -> session.completion().toCompletableFuture().join());
            assertCompletionObservation(observation, LaunchStatus.FAILED, TaskStatus.FAILED);
        } finally {
            service.close();
        }
    }

    /// Verifies cancellation state visible from a completion callback before queued startup ever runs.
    private static void verifyCancelledCompletionObservation() {
        QueuedExecutor preparationExecutor = new QueuedExecutor();
        AtomicInteger factoryCalls = new AtomicInteger();
        DefaultGameLaunchService service = service(request -> {
            factoryCalls.incrementAndGet();
            return new CompletedLaunchTask(managedProcess(new RecordingProcess()));
        }, preparationExecutor);
        try {
            LaunchSession session = service.launch(request("completion-cancellation"));
            CompletableFuture<CompletionObservation> observation = observeCompletion(session);
            assertTrue(session.cancel());
            preparationExecutor.runPending();
            assertThrows(
                    CompletionException.class,
                    () -> session.completion().toCompletableFuture().join());
            assertCompletionObservation(observation, LaunchStatus.CANCELLED, TaskStatus.CANCELLED);
            assertEquals(0, factoryCalls.get());
        } finally {
            service.close();
        }
    }

    /// Verifies a task failure that occurs after cancellation remains the authoritative terminal outcome.
    ///
    /// @param failure exception or error raised after cancellation is accepted
    private static void verifyCancellationThenFailure(Throwable failure) throws Exception {
        CountDownLatch taskEntered = new CountDownLatch(1);
        CountDownLatch taskRelease = new CountDownLatch(1);
        DefaultGameLaunchService service = service(request -> new FailureAfterCancellationTask(
                failure,
                taskEntered,
                taskRelease));
        try {
            LaunchSession session = service.launch(request("cancel-then-fail"));
            assertTrue(taskEntered.await(5, TimeUnit.SECONDS));
            assertTrue(session.cancel());
            taskRelease.countDown();

            CompletionException completionFailure = assertThrows(
                    CompletionException.class,
                    () -> session.completion().toCompletableFuture().join());
            assertSame(failure, completionFailure.getCause());
            assertSame(failure, session.failure().orElseThrow());
            assertEquals(LaunchStatus.FAILED, session.status());
            assertEquals(TaskStatus.FAILED, session.snapshot().status());
            assertTrue(service.activePreparation().isEmpty());
        } finally {
            taskRelease.countDown();
            service.close();
        }
    }

    /// Registers a synchronous completion callback that captures every public terminal state surface.
    ///
    /// @param session launch session to observe
    /// @return future completed by the registered callback after it captures every state surface
    private static CompletableFuture<CompletionObservation> observeCompletion(LaunchSession session) {
        CompletableFuture<CompletionObservation> observation = new CompletableFuture<>();
        session.completion().whenComplete((
                @Nullable ManagedProcess process,
                @Nullable Throwable failure) -> observation.complete(new CompletionObservation(
                        session.status(),
                        Objects.requireNonNull(session.statusProperty().getValue(), "terminal status property"),
                        session.snapshot().status(),
                        session.snapshot().cancelable())));
        return observation;
    }

    /// Asserts one callback captured coherent terminal state and disabled cancellation.
    ///
    /// @param observation callback-owned observation future
    /// @param expectedLaunchStatus expected launch lifecycle state
    /// @param expectedTaskStatus expected task presentation state
    private static void assertCompletionObservation(
            CompletableFuture<CompletionObservation> observation,
            LaunchStatus expectedLaunchStatus,
            TaskStatus expectedTaskStatus) {
        CompletionObservation captured = observation.join();
        assertEquals(expectedLaunchStatus, captured.launchStatus());
        assertEquals(expectedLaunchStatus, captured.observableLaunchStatus());
        assertEquals(expectedTaskStatus, captured.taskStatus());
        assertFalse(captured.cancelable());
    }

    /// Waits for a latch despite interruption, then restores the worker interruption flag.
    ///
    /// @param latch latch that must open before returning
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /// Waits at a common barrier, then maps only the single-flight loser to an empty result.
    ///
    /// @param service shared launch service
    /// @param startBarrier concurrent-call barrier
    /// @return winning session or an empty value for the rejected caller
    private static Optional<LaunchSession> launchAfterBarrier(
            DefaultGameLaunchService service,
            CyclicBarrier startBarrier) throws Exception {
        startBarrier.await(5, TimeUnit.SECONDS);
        try {
            return Optional.of(service.launch(request("concurrent")));
        } catch (LaunchAlreadyRunningException ignored) {
            return Optional.empty();
        }
    }

    /// Creates one request whose account and directory identifiers stay fixed across test instances.
    ///
    /// @param instanceId stable test instance identifier
    /// @return immutable launch request
    private static LaunchRequest request(String instanceId) {
        return new LaunchRequest("account-id", "directory-id", new GameInstanceID(instanceId));
    }

    /// Creates a service with explicit test-only presentation strings.
    ///
    /// @param taskFactory task factory under test
    /// @return launch service using stable explicit presentation text
    private static DefaultGameLaunchService service(LaunchTaskFactory taskFactory) {
        return service(taskFactory, ForkJoinPool.commonPool());
    }

    /// Creates a service with explicit test-only presentation strings and preparation executor.
    ///
    /// @param taskFactory task factory under test
    /// @param preparationExecutor explicitly caller-owned preparation executor
    /// @return launch service using stable explicit presentation text
    private static DefaultGameLaunchService service(
            LaunchTaskFactory taskFactory,
            Executor preparationExecutor) {
        return new DefaultGameLaunchService(
                taskFactory,
                preparationExecutor,
                "Test launch",
                "Waiting for test preparation");
    }

    /// Wraps a recording raw process in the production managed-process boundary.
    ///
    /// @param process raw process fixture
    /// @return managed process with a stable non-empty command line
    private static ManagedProcess managedProcess(RecordingProcess process) {
        return new ManagedProcess(process, List.of("java", "test.Main"));
    }

    /// Terminal state captured synchronously from one process-completion callback.
    ///
    /// @param launchStatus direct launch-session status
    /// @param observableLaunchStatus launch status property value
    /// @param taskStatus task-presentation status
    /// @param cancelable task-presentation cancellation availability
    @NotNullByDefault
    private record CompletionObservation(
            LaunchStatus launchStatus,
            LaunchStatus observableLaunchStatus,
            TaskStatus taskStatus,
            boolean cancelable) {
        /// Validates one complete terminal observation.
        private CompletionObservation {
            Objects.requireNonNull(launchStatus, "launchStatus");
            Objects.requireNonNull(observableLaunchStatus, "observableLaunchStatus");
            Objects.requireNonNull(taskStatus, "taskStatus");
        }
    }

    /// Caller-controlled executor that stores exactly one preparation command until explicitly run.
    @NotNullByDefault
    private static final class QueuedExecutor implements Executor {
        /// Pending command, or null before submission and after execution.
        private final AtomicReference<@Nullable Runnable> pending = new AtomicReference<>();

        /// Stores one command without running it inline.
        ///
        /// @param command preparation command to queue
        @Override
        public void execute(Runnable command) {
            Objects.requireNonNull(command, "command");
            if (!pending.compareAndSet(null, command)) {
                throw new RejectedExecutionException("test executor already has a pending command");
            }
        }

        /// Runs and removes the pending command on the calling test thread.
        private void runPending() {
            Runnable command = Objects.requireNonNull(pending.getAndSet(null), "pending command");
            command.run();
        }
    }

    /// Task that records whether a late factory result was ever executed.
    @NotNullByDefault
    private static final class CountingLaunchTask extends Task<ManagedProcess> {
        /// Process published if execution is incorrectly or correctly started.
        private final ManagedProcess process;

        /// Shared execution counter.
        private final AtomicInteger executions;

        /// Creates a task that records execution before producing its process.
        ///
        /// @param process successful task result
        /// @param executions shared execution counter
        private CountingLaunchTask(ManagedProcess process, AtomicInteger executions) {
            this.process = process;
            this.executions = executions;
        }

        /// Records execution and publishes the process.
        @Override
        public void execute() {
            executions.incrementAndGet();
            setResult(process);
        }
    }

    /// Task that delays cancellation observation and allows deterministic concurrent progress updates.
    @NotNullByDefault
    private static final class DeferredCancellationTask extends Task<ManagedProcess> {
        /// Process published only when execution was not cancelled before release.
        private final ManagedProcess process;

        /// Signals that task execution has started.
        private final CountDownLatch entered;

        /// Controls when the task observes executor cancellation.
        private final CountDownLatch release;

        /// Creates one task with explicitly controlled cancellation observation.
        ///
        /// @param process successful result when not cancelled
        /// @param entered execution-start signal
        /// @param release cancellation-observation control
        private DeferredCancellationTask(
                ManagedProcess process,
                CountDownLatch entered,
                CountDownLatch release) {
            this.process = process;
            this.entered = entered;
            this.release = release;
        }

        /// Waits until released, then maps the executor cancellation flag to task cancellation.
        @Override
        public void execute() throws Exception {
            entered.countDown();
            release.await();
            if (isCancelled()) {
                throw new CancellationException("cancelled after controlled release");
            }
            setResult(process);
        }

        /// Publishes a deterministic adapter update from the calling test thread.
        ///
        /// @param progress normalized test progress
        private void reportProgress(double progress) {
            updateProgressImmediately(progress);
        }
    }

    /// Task that waits cooperatively until released or cancelled before publishing its process.
    @NotNullByDefault
    private static final class BlockingLaunchTask extends Task<ManagedProcess> {
        /// Process published after normal release.
        private final ManagedProcess process;

        /// Signals that task execution has started.
        private final CountDownLatch entered;

        /// Controls normal task completion.
        private final CountDownLatch release;

        /// Creates a controllable preparation task.
        ///
        /// @param process result produced after release
        /// @param entered execution-start signal
        /// @param release normal-completion control
        private BlockingLaunchTask(
                ManagedProcess process,
                CountDownLatch entered,
                CountDownLatch release) {
            this.process = process;
            this.entered = entered;
            this.release = release;
        }

        /// Waits in short bounded intervals so executor cancellation is observed without thread interruption.
        @Override
        public void execute() throws Exception {
            entered.countDown();
            while (!release.await(10, TimeUnit.MILLISECONDS)) {
                if (isCancelled()) {
                    throw new CancellationException("cancelled by test");
                }
            }
            if (isCancelled()) {
                throw new CancellationException("cancelled by test");
            }
            setResult(process);
        }
    }

    /// Task that publishes one process immediately without reporting invented progress values.
    @NotNullByDefault
    private static final class CompletedLaunchTask extends Task<ManagedProcess> {
        /// Process returned by this task.
        private final ManagedProcess process;

        /// Creates an immediate successful launch task.
        ///
        /// @param process task result
        private CompletedLaunchTask(ManagedProcess process) {
            this.process = process;
        }

        /// Publishes the prepared process.
        @Override
        public void execute() {
            setResult(process);
        }
    }

    /// Task that terminates with one stable checked failure.
    @NotNullByDefault
    private static final class FailingLaunchTask extends Task<ManagedProcess> {
        /// Failure thrown from execution.
        private final IOException failure;

        /// Creates a failing task.
        ///
        /// @param failure stable expected failure
        private FailingLaunchTask(IOException failure) {
            this.failure = failure;
        }

        /// Throws the configured preparation failure.
        @Override
        public void execute() throws IOException {
            throw failure;
        }
    }

    /// Task that terminates with one stable error rather than an exception.
    @NotNullByDefault
    private static final class ErrorLaunchTask extends Task<ManagedProcess> {
        /// Error thrown from execution.
        private final AssertionError failure;

        /// Creates an erroring task.
        ///
        /// @param failure stable expected error
        private ErrorLaunchTask(AssertionError failure) {
            this.failure = failure;
        }

        /// Throws the configured task error.
        @Override
        public void execute() {
            throw failure;
        }
    }

    /// Assertion error fixture whose stack-trace rendering itself fails.
    @NotNullByDefault
    private static final class StackTraceThrowingError extends AssertionError {
        /// Stable error raised whenever diagnostic rendering is attempted.
        private final AssertionError formattingFailure;

        /// Creates one failure whose stack trace cannot be rendered.
        ///
        /// @param formattingFailure error raised by stack-trace rendering
        private StackTraceThrowingError(AssertionError formattingFailure) {
            super("test launch failure with an unavailable stack trace");
            this.formattingFailure = formattingFailure;
        }

        /// Rejects stack-trace rendering with the configured stable error.
        ///
        /// @param writer ignored destination requested by the formatter
        @Override
        public void printStackTrace(PrintWriter writer) {
            throw formattingFailure;
        }
    }

    /// Task that delays a configured real failure until after cancellation can be accepted.
    @NotNullByDefault
    private static final class FailureAfterCancellationTask extends Task<ManagedProcess> {
        /// Exception or error raised after release.
        private final Throwable failure;

        /// Signals task execution has started.
        private final CountDownLatch entered;

        /// Controls failure delivery.
        private final CountDownLatch release;

        /// Creates one cancellation-race failure task.
        ///
        /// @param failure exception or error to throw
        /// @param entered execution-start signal
        /// @param release failure-delivery control
        private FailureAfterCancellationTask(
                Throwable failure,
                CountDownLatch entered,
                CountDownLatch release) {
            this.failure = failure;
            this.entered = entered;
            this.release = release;
        }

        /// Waits for release, then throws the configured real failure even when cancellation was accepted.
        @Override
        public void execute() throws Exception {
            entered.countDown();
            release.await();
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new AssertionError("Unsupported test failure type", failure);
        }
    }

    /// Minimal raw process fixture that records destruction without starting an operating-system process.
    @NotNullByDefault
    private static final class RecordingProcess extends Process {
        /// Writable standard input fixture.
        private final ByteArrayOutputStream standardInput = new ByteArrayOutputStream();

        /// Number of calls to [#destroy()].
        private final AtomicInteger destroyCalls = new AtomicInteger();

        /// Whether this fixture still reports itself as alive.
        private volatile boolean alive = true;

        /// Returns the writable process input fixture.
        @Override
        public OutputStream getOutputStream() {
            return standardInput;
        }

        /// Returns an empty standard output stream.
        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        /// Returns an empty standard error stream.
        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        /// Marks the fixture exited and returns its successful exit code.
        @Override
        public int waitFor() {
            alive = false;
            return 0;
        }

        /// Returns the exit code or reports that the fixture is still alive.
        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("process is still alive");
            }
            return 0;
        }

        /// Records one stop request and marks the fixture exited.
        @Override
        public void destroy() {
            destroyCalls.incrementAndGet();
            alive = false;
        }

        /// Returns the current fixture liveness state.
        @Override
        public boolean isAlive() {
            return alive;
        }

        /// Returns how many stop requests were made.
        ///
        /// @return destruction call count
        private int destroyCalls() {
            return destroyCalls.get();
        }
    }
}
