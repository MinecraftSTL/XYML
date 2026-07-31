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
package space.minecraftstl.xyml.game.install;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.presentation.TaskStatus;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies request fidelity, single-flight ownership, cancellation, and exact terminal failures.
@NotNullByDefault
final class DefaultGameInstallServiceTest {
    /// Request validation preserves accepted text and rejects null or blank values.
    @Test
    void requestPreservesExactValuesAndRejectsMissingText() {
        GameInstallRequest request = new GameInstallRequest("  custom instance  ", " 1.21.1 ");

        assertEquals("  custom instance  ", request.instanceName());
        assertEquals(" 1.21.1 ", request.versionId());
        assertThrows(NullPointerException.class, () -> new GameInstallRequest(null, "1.21.1"));
        assertThrows(NullPointerException.class, () -> new GameInstallRequest("instance", null));
        assertThrows(IllegalArgumentException.class, () -> new GameInstallRequest("\t", "1.21.1"));
        assertThrows(IllegalArgumentException.class, () -> new GameInstallRequest("instance", " "));
    }

    /// A running request owns the slot until terminal cleanup and exposes task presentation.
    @Test
    @Timeout(10)
    void activeInstallRejectsSecondRequestAndReleasesSlotOnSuccess() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger factoryCalls = new AtomicInteger();
        BlockingInstallTask task = new BlockingInstallTask(entered, release);
        task.setName("Downloading vanilla files");
        DefaultGameInstallService service = service(request -> {
            factoryCalls.incrementAndGet();
            return task;
        });
        try {
            GameInstallRequest firstRequest = request("first");
            GameInstallSession first = service.install(firstRequest);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(TaskStatus.RUNNING, first.snapshot().status());
            assertEquals("Downloading vanilla files", first.snapshot().phase());
            assertTrue(first.snapshot().cancelable());

            GameInstallRequest secondRequest = request("second");
            GameInstallAlreadyRunningException conflict = assertThrows(
                    GameInstallAlreadyRunningException.class,
                    () -> service.install(secondRequest));
            assertEquals(firstRequest, conflict.activeRequest());
            assertEquals(1, factoryCalls.get());

            release.countDown();
            first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(GameInstallStatus.COMPLETED, first.status());
            assertEquals(TaskStatus.SUCCEEDED, first.snapshot().status());
            assertTrue(service.activeInstallation().isEmpty());
        } finally {
            release.countDown();
            service.close();
        }
    }

    /// A real task failure preserves identity, releases the slot, and permits a later retry.
    @Test
    @Timeout(10)
    void failurePreservesIdentityAndAllowsRetry() throws Exception {
        IOException failure = new IOException("installation failed");
        AtomicInteger factoryCalls = new AtomicInteger();
        DefaultGameInstallService service = service(request -> factoryCalls.incrementAndGet() == 1
                ? new FailingInstallTask(failure)
                : new CompletedInstallTask());
        try {
            GameInstallSession failed = service.install(request("failed"));
            CompletionException completionFailure = assertThrows(
                    CompletionException.class,
                    () -> failed.completion().toCompletableFuture().join());
            assertSame(failure, completionFailure.getCause());
            assertSame(failure, failed.failure().orElseThrow());
            assertEquals(GameInstallStatus.FAILED, failed.status());
            assertTrue(service.activeInstallation().isEmpty());

            GameInstallSession retry = service.install(request("retry"));
            retry.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(GameInstallStatus.COMPLETED, retry.status());
            assertEquals(2, factoryCalls.get());
        } finally {
            service.close();
        }
    }

    /// An Error raised by asynchronous task execution remains the exact failed-session cause.
    @Test
    @Timeout(10)
    void taskErrorPreservesIdentity() {
        AssertionError failure = new AssertionError("installation task error");
        DefaultGameInstallService service = service(request -> new FailingInstallTask(failure));
        try {
            GameInstallSession session = service.install(request("error"));
            CompletionException completionFailure = assertThrows(
                    CompletionException.class,
                    () -> session.completion().toCompletableFuture().join());
            assertSame(failure, completionFailure.getCause());
            assertSame(failure, session.failure().orElseThrow());
            assertEquals(GameInstallStatus.FAILED, session.status());
            assertEquals(TaskStatus.FAILED, session.snapshot().status());
            assertTrue(service.activeInstallation().isEmpty());
        } finally {
            service.close();
        }
    }

    /// Closing cancels active work, clears ownership, and rejects all future requests.
    @Test
    @Timeout(10)
    void closeCancelsActiveInstallAndRejectsLaterRequests() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DefaultGameInstallService service = service(request -> new BlockingInstallTask(entered, release));

        GameInstallSession session = service.install(request("cancelled"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        service.close();

        assertCancelled(session);
        assertEquals(GameInstallStatus.CANCELLED, session.status());
        assertEquals(TaskStatus.CANCELLED, session.snapshot().status());
        assertTrue(service.activeInstallation().isEmpty());
        assertFalse(session.cancel());
        assertThrows(IllegalStateException.class, () -> service.install(request("after-close")));
        release.countDown();
    }

    /// Cancellation while the factory is blocked prevents its late task from ever starting.
    @Test
    @Timeout(10)
    void cancellationDuringFactoryInvocationDiscardsLateTask() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch factoryRelease = new CountDownLatch(1);
        CountDownLatch factoryReturned = new CountDownLatch(1);
        AtomicInteger taskExecutions = new AtomicInteger();
        ExecutorService preparationExecutor = Executors.newSingleThreadExecutor();
        DefaultGameInstallService service = new DefaultGameInstallService(
                request -> {
                    factoryEntered.countDown();
                    try {
                        try {
                            factoryRelease.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new CancellationException("factory interrupted");
                        }
                    } finally {
                        factoryReturned.countDown();
                    }
                    return new CountingInstallTask(taskExecutions);
                },
                preparationExecutor,
                "Install game",
                "Preparing installation");
        try {
            GameInstallSession session = service.install(request("factory-cancel"));
            assertTrue(factoryEntered.await(5, TimeUnit.SECONDS));
            assertTrue(session.cancel());
            assertCancelled(session);
            assertTrue(service.activeInstallation().isEmpty());

            factoryRelease.countDown();
            assertTrue(factoryReturned.await(5, TimeUnit.SECONDS));
            assertEquals(0, taskExecutions.get());
        } finally {
            factoryRelease.countDown();
            service.close();
            preparationExecutor.shutdownNow();
        }
    }

    /// A real task failure after accepted cancellation remains the authoritative terminal outcome.
    @Test
    @Timeout(10)
    void cancellationDoesNotMaskRealTaskFailure() throws Exception {
        IOException failure = new IOException("failure after cancellation");
        CountDownLatch entered = new CountDownLatch(1);
        DefaultGameInstallService service = service(request ->
                new CancellationThenFailureTask(entered, failure));
        try {
            GameInstallSession session = service.install(request("cancel-failure"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(session.cancel());

            CompletionException completionFailure = assertThrows(
                    CompletionException.class,
                    () -> session.completion().toCompletableFuture().join());
            assertSame(failure, completionFailure.getCause());
            assertSame(failure, session.failure().orElseThrow());
            assertEquals(GameInstallStatus.FAILED, session.status());
            assertTrue(service.activeInstallation().isEmpty());
        } finally {
            service.close();
        }
    }

    /// An interrupted task matches the core presentation model's cancellation classification.
    @Test
    @Timeout(10)
    void interruptedTaskMapsToCancellation() {
        DefaultGameInstallService service = service(request -> new InterruptedInstallTask());
        try {
            GameInstallSession session = service.install(request("interrupted"));
            assertCancelled(session);
            assertEquals(GameInstallStatus.CANCELLED, session.status());
            assertEquals(TaskStatus.CANCELLED, session.snapshot().status());
            assertTrue(session.failure().isEmpty());
        } finally {
            service.close();
        }
    }

    /// Preparation-executor rejection becomes a failed returned session and releases ownership.
    @Test
    void rejectedPreparationFailsSessionWithoutCallingFactory() {
        RejectedExecutionException rejection = new RejectedExecutionException("executor rejected install");
        AtomicInteger factoryCalls = new AtomicInteger();
        DefaultGameInstallService service = new DefaultGameInstallService(
                request -> {
                    factoryCalls.incrementAndGet();
                    return new CompletedInstallTask();
                },
                command -> {
                    throw rejection;
                },
                "Install game",
                "Preparing installation");
        try {
            GameInstallSession session = service.install(request("rejected"));
            CompletionException completionFailure = assertThrows(
                    CompletionException.class,
                    () -> session.completion().toCompletableFuture().join());
            assertSame(rejection, completionFailure.getCause());
            assertSame(rejection, session.failure().orElseThrow());
            assertEquals(0, factoryCalls.get());
            assertTrue(service.activeInstallation().isEmpty());
        } finally {
            service.close();
        }
    }

    /// Creates a direct-preparation service whose task executor remains asynchronous.
    ///
    /// @param taskFactory deterministic task factory
    /// @return test installation service
    private static DefaultGameInstallService service(GameInstallTaskFactory taskFactory) {
        return new DefaultGameInstallService(
                taskFactory,
                Runnable::run,
                "Install game",
                "Preparing installation");
    }

    /// Creates a stable request using the suffix only to distinguish instance names.
    ///
    /// @param suffix instance-name suffix
    /// @return immutable request
    private static GameInstallRequest request(String suffix) {
        return new GameInstallRequest("instance-" + suffix, "1.21.1");
    }

    /// Verifies cancellation through the minimal completion-stage view.
    ///
    /// @param session cancelled session
    private static void assertCancelled(GameInstallSession session) {
        try {
            session.completion().toCompletableFuture().join();
            throw new AssertionError("Expected cancelled installation completion");
        } catch (CancellationException cancellation) {
            // Expected direct CompletableFuture cancellation.
        } catch (CompletionException completionFailure) {
            assertTrue(completionFailure.getCause() instanceof CancellationException);
        }
    }

    /// Blocks until released and maps interruption to cooperative cancellation.
    @NotNullByDefault
    private static final class BlockingInstallTask extends Task<@Nullable Void> {
        /// Signals that asynchronous execution began.
        private final CountDownLatch entered;

        /// Controls completion of the simulated installation.
        private final CountDownLatch release;

        /// Creates a controlled installation task.
        ///
        /// @param entered execution-start signal
        /// @param release completion gate
        private BlockingInstallTask(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        /// Waits in bounded intervals so cooperative executor cancellation is observed.
        @Override
        public void execute() throws Exception {
            entered.countDown();
            while (!release.await(10, TimeUnit.MILLISECONDS)) {
                if (isCancelled()) {
                    throw new CancellationException("installation task cancelled");
                }
            }
            if (isCancelled()) {
                throw new CancellationException("installation task cancelled");
            }
        }
    }

    /// Completes without producing a value.
    @NotNullByDefault
    private static final class CompletedInstallTask extends Task<@Nullable Void> {
        /// Creates a successful task.
        private CompletedInstallTask() {
        }

        /// Completes normally.
        @Override
        public void execute() {
        }
    }

    /// Throws one exact checked exception or Error from task execution.
    @NotNullByDefault
    private static final class FailingInstallTask extends Task<@Nullable Void> {
        /// Exact failure emitted by execution.
        private final Throwable failure;

        /// Creates a failing task.
        ///
        /// @param failure checked exception or Error to emit
        private FailingInstallTask(Throwable failure) {
            this.failure = failure;
        }

        /// Throws the configured failure unchanged.
        @Override
        public void execute() throws Exception {
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Unsupported throwable type", failure);
        }
    }

    /// Records execution when a late factory result is incorrectly started.
    @NotNullByDefault
    private static final class CountingInstallTask extends Task<@Nullable Void> {
        /// Shared execution counter.
        private final AtomicInteger executions;

        /// Creates a counting task.
        ///
        /// @param executions shared execution counter
        private CountingInstallTask(AtomicInteger executions) {
            this.executions = executions;
        }

        /// Records execution.
        @Override
        public void execute() {
            executions.incrementAndGet();
        }
    }

    /// Waits for cancellation and then reports an independent real failure.
    @NotNullByDefault
    private static final class CancellationThenFailureTask extends Task<@Nullable Void> {
        /// Signals that task execution began.
        private final CountDownLatch entered;

        /// Real failure emitted after cancellation becomes visible.
        private final IOException failure;

        /// Creates a cancellation-race task.
        ///
        /// @param entered execution-start signal
        /// @param failure real failure emitted after cancellation
        private CancellationThenFailureTask(CountDownLatch entered, IOException failure) {
            this.entered = entered;
            this.failure = failure;
        }

        /// Polls cooperative cancellation before throwing the configured failure.
        @Override
        public void execute() throws Exception {
            entered.countDown();
            while (!isCancelled()) {
                Thread.sleep(10L);
            }
            throw failure;
        }
    }

    /// Emits InterruptedException to verify cancellation classification parity.
    @NotNullByDefault
    private static final class InterruptedInstallTask extends Task<@Nullable Void> {
        /// Creates an interrupted task.
        private InterruptedInstallTask() {
        }

        /// Stops with InterruptedException.
        @Override
        public void execute() throws Exception {
            throw new InterruptedException("installation interrupted");
        }
    }
}
