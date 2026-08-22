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
package space.minecraftstl.xyml.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.util.function.ExceptionalRunnable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies executor-level resource ownership across complete task lifecycles and terminal failure paths.
@NotNullByDefault
public final class AsyncTaskExecutorResourceTest {
    /// Maximum duration for every bounded asynchronous assertion.
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /// Temporary filesystem root used to create independent semantic resources.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies independent executors cannot run bodies holding the same exact resource concurrently.
    @Test
    public void sameResourceSerializesIndependentExecutors() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource resource = target("same.jar");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        Task<?> first = task(resource, () -> {
            firstStarted.countDown();
            await(releaseFirst);
        });
        Task<?> second = task(resource, secondStarted::countDown);

        CompletableFuture<Boolean> firstResult = execute(first, manager);
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Boolean> secondResult = execute(second, manager);
        awaitCondition(() -> manager.pendingWaiterCount() == 1);

        assertFalse(secondStarted.await(200, TimeUnit.MILLISECONDS));
        releaseFirst.countDown();

        assertTrue(get(firstResult));
        assertTrue(get(secondResult));
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies independent exact resources reach a barrier concurrently rather than being globally serialized.
    @Test
    public void differentResourcesExecuteInParallel() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        CyclicBarrier barrier = new CyclicBarrier(2);
        Task<?> first = task(target("first.jar"), () -> barrier.await(5, TimeUnit.SECONDS));
        Task<?> second = task(target("second.jar"), () -> barrier.await(5, TimeUnit.SECONDS));

        CompletableFuture<Boolean> firstResult = execute(first, manager);
        CompletableFuture<Boolean> secondResult = execute(second, manager);

        assertTrue(get(firstResult));
        assertTrue(get(secondResult));
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies one regular task retains its lease through every nested phase and its finished listener.
    @Test
    public void normalTaskHoldsResourceThroughCompleteLifecycle() {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource resource = target("lifecycle.jar");
        List<String> phases = new java.util.concurrent.CopyOnWriteArrayList<>();
        Task<?> dependent = task(resource, () -> phases.add("dependent"));
        Task<?> dependency = task(resource, () -> phases.add("dependency"));
        AtomicReference<@Nullable CompletableFuture<Boolean>> competitorResult = new AtomicReference<>();
        AtomicBoolean competitorStarted = new AtomicBoolean();
        Task<?> competitor = task(resource, () -> {
            competitorStarted.set(true);
            phases.add("competitor");
        });
        Task<Void> root = lifecycleTask(manager, resource, dependent, dependency, phases, competitorStarted);
        AsyncTaskExecutor executor = new AsyncTaskExecutor(root, manager);
        executor.subscribeTaskListener(new TaskListener() {
            /// Starts a conflicting executor only after the root has acquired its lease.
            @Override
            public void onReady(Task<?> readyTask) {
                if (readyTask == root) {
                    competitorResult.set(execute(competitor, manager));
                }
            }

            /// Records completion-listener delivery while the root still owns its resource.
            @Override
            public void onFinished(Task<?> finishedTask) {
                if (finishedTask == root) {
                    phases.add("finished");
                }
            }
        });

        assertTrue(assertTimeoutPreemptively(TIMEOUT, executor::test));
        @Nullable CompletableFuture<Boolean> nullableResult = competitorResult.get();
        assertNotNull(nullableResult);
        CompletableFuture<Boolean> result = Objects.requireNonNull(nullableResult);
        assertTrue(get(result));
        assertEquals(List.of("pre", "dependent", "execute", "dependency", "post", "finished", "competitor"),
                phases);
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies same-resource siblings remain independent owners even though both reenter their parent coverage.
    @Test
    public void conflictingSiblingsSerializeWithinOneExecutionChain() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        Path instancePath = temporaryDirectory.resolve("instances/example");
        TaskResource instance = TaskResource.gameInstance(instancePath);
        TaskResource childResource = TaskResource.downloadTarget(instancePath.resolve("example.jar"));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        CountDownLatch oneChildStarted = new CountDownLatch(1);
        CountDownLatch releaseChildren = new CountDownLatch(1);
        Task<?> first = blockingSibling(childResource, active, maximumActive, oneChildStarted, releaseChildren);
        Task<?> second = blockingSibling(childResource, active, maximumActive, oneChildStarted, releaseChildren);
        Task<Void> parent = parentTask(instance, List.of(first, second));

        CompletableFuture<Boolean> result = execute(parent, manager);
        assertTrue(oneChildStarted.await(5, TimeUnit.SECONDS));
        awaitCondition(() -> manager.pendingWaiterCount() == 1);

        assertEquals(1, maximumActive.get());
        releaseChildren.countDown();
        assertTrue(get(result));
        assertEquals(1, maximumActive.get());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a completable-future task retains its lease until the supplied future reaches a terminal state.
    @Test
    public void completableFutureTaskHoldsResourceUntilFutureCompletion() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource resource = target("future.jar");
        CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();
        Task<?> futureTask = Task.fromCompletableFuture(completion).setResources(resource);
        CountDownLatch futureReady = new CountDownLatch(1);
        AtomicBoolean competitorRan = new AtomicBoolean();
        Task<?> competitor = task(resource, () -> competitorRan.set(true));
        AsyncTaskExecutor futureExecutor = new AsyncTaskExecutor(futureTask, manager);
        futureExecutor.subscribeTaskListener(new TaskListener() {
            /// Signals that the future task has acquired its resource and requested the supplied future.
            @Override
            public void onReady(Task<?> readyTask) {
                if (readyTask == futureTask) {
                    futureReady.countDown();
                }
            }
        });

        CompletableFuture<Boolean> futureResult = CompletableFuture.supplyAsync(futureExecutor::test);
        assertTrue(futureReady.await(5, TimeUnit.SECONDS));
        CompletableFuture<Boolean> competitorResult = execute(competitor, manager);
        awaitCondition(() -> manager.pendingWaiterCount() == 1);

        assertFalse(competitorRan.get());
        completion.complete(null);
        assertTrue(get(futureResult));
        assertTrue(get(competitorResult));
        assertTrue(competitorRan.get());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies successful, exceptional, error, interrupted, and rejected regular tasks all release their resources.
    @Test
    public void regularTerminalPathsAlwaysReleaseResources() {
        assertTerminalPathReleases("success.jar", Task.runAsync(() -> {
        }), true);
        assertTerminalPathReleases("exception.jar", Task.runAsync(() -> {
            throw new IOException("failure");
        }), false);
        assertTerminalPathReleases("error.jar", Task.runAsync(() -> {
            throw new AssertionError("failure");
        }), false);
        assertTerminalPathReleases("interrupted.jar", Task.runAsync(() -> {
            throw new InterruptedException("interrupted");
        }), false);
        assertTerminalPathReleases("rejected.jar", Task.runAsync(command -> {
            throw new RejectedExecutionException("rejected");
        }, () -> {
        }), false);
    }

    /// Verifies failed and cancelled completable futures also release their resources after terminal handling.
    @Test
    public void completableFutureTerminalPathsAlwaysReleaseResources() {
        assertTerminalPathReleases(
                "future-failure.jar",
                Task.fromCompletableFuture(CompletableFuture.failedFuture(new IOException("failure"))),
                false);
        CompletableFuture<@Nullable Void> cancelledFuture = new CompletableFuture<>();
        cancelledFuture.cancel(false);
        assertTerminalPathReleases(
                "future-cancel.jar",
                Task.fromCompletableFuture(cancelledFuture),
                false);
    }

    /// Verifies an error raised by a terminal listener cannot leak the completed task's lease.
    @Test
    public void listenerErrorReleasesResource() {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource resource = target("listener-error.jar");
        Task<?> task = task(resource, () -> {
        });
        AsyncTaskExecutor executor = new AsyncTaskExecutor(task, manager);
        executor.subscribeTaskListener(new TaskListener() {
            /// Raises a terminal listener error after the task body succeeds.
            @Override
            public void onFinished(Task<?> finishedTask) {
                if (finishedTask == task) {
                    throw new AssertionError("listener failure");
                }
            }
        });

        assertFalse(assertTimeoutPreemptively(TIMEOUT, executor::test));
        assertSuccessorRuns(manager, resource);
    }

    /// Verifies cancelling a waiter removes it, skips its body, and leaves no resource bookkeeping behind.
    @Test
    public void cancellingWaitingExecutorRemovesPendingAcquisition() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource resource = target("waiting-cancel.jar");
        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        AtomicBoolean waiterRan = new AtomicBoolean();
        Task<?> holder = task(resource, () -> {
            holderStarted.countDown();
            await(releaseHolder);
        });
        Task<?> waiter = task(resource, () -> waiterRan.set(true));
        AsyncTaskExecutor holderExecutor = new AsyncTaskExecutor(holder, manager);
        AsyncTaskExecutor waiterExecutor = new AsyncTaskExecutor(waiter, manager);

        CompletableFuture<Boolean> holderResult = CompletableFuture.supplyAsync(holderExecutor::test);
        assertTrue(holderStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Boolean> waiterResult = CompletableFuture.supplyAsync(waiterExecutor::test);
        awaitCondition(() -> manager.pendingWaiterCount() == 1);
        waiterExecutor.cancel();

        assertFalse(get(waiterResult));
        assertFalse(waiterRan.get());
        assertEquals(0, manager.pendingWaiterCount());
        releaseHolder.countDown();
        assertTrue(get(holderResult));
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies cancelling a current holder releases it after its body returns and unblocks the next waiter.
    @Test
    public void cancellingHolderAllowsWaitingExecutorToContinue() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource resource = target("holder-cancel.jar");
        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        AtomicBoolean waiterRan = new AtomicBoolean();
        Task<?> holder = task(resource, () -> {
            holderStarted.countDown();
            await(releaseHolder);
        });
        Task<?> waiter = task(resource, () -> waiterRan.set(true));
        AsyncTaskExecutor holderExecutor = new AsyncTaskExecutor(holder, manager);

        CompletableFuture<Boolean> holderResult = CompletableFuture.supplyAsync(holderExecutor::test);
        assertTrue(holderStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Boolean> waiterResult = execute(waiter, manager);
        awaitCondition(() -> manager.pendingWaiterCount() == 1);
        holderExecutor.cancel();
        releaseHolder.countDown();

        assertFalse(get(holderResult));
        assertTrue(get(waiterResult));
        assertTrue(waiterRan.get());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Creates a regular task with one explicit resource declaration.
    private static Task<@Nullable Void> task(TaskResource resource, ExceptionalRunnable<?> action) {
        return Task.runAsync(action).setResources(resource);
    }

    /// Creates a lifecycle task whose phases and nested tasks all reuse one resource owner chain.
    private static Task<Void> lifecycleTask(
            TaskResourceLockManager manager,
            TaskResource resource,
            Task<?> dependent,
            Task<?> dependency,
            List<String> phases,
            AtomicBoolean competitorStarted) {
        return new Task<Void>() {
            /// Enables pre-execution work.
            @Override
            public boolean doPreExecute() {
                return true;
            }

            /// Records pre-execution and waits until the conflicting root is queued.
            @Override
            public void preExecute() {
                phases.add("pre");
                awaitCondition(() -> manager.pendingWaiterCount() == 1);
                assertFalse(competitorStarted.get());
            }

            /// Returns the nested prerequisite.
            @Override
            public @Unmodifiable List<Task<?>> getDependents() {
                return List.of(dependent);
            }

            /// Records primary execution.
            @Override
            public void execute() {
                assertFalse(competitorStarted.get());
                phases.add("execute");
            }

            /// Returns the nested follow-up.
            @Override
            public @Unmodifiable List<Task<?>> getDependencies() {
                return List.of(dependency);
            }

            /// Enables post-execution work.
            @Override
            public boolean doPostExecute() {
                return true;
            }

            /// Records post-execution.
            @Override
            public void postExecute() {
                assertFalse(competitorStarted.get());
                phases.add("post");
            }
        }.setResources(resource);
    }

    /// Creates a child task that records the maximum number of simultaneously active siblings.
    private static Task<@Nullable Void> blockingSibling(
            TaskResource resource,
            AtomicInteger active,
            AtomicInteger maximumActive,
            CountDownLatch oneChildStarted,
            CountDownLatch releaseChildren) {
        return task(resource, () -> {
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            oneChildStarted.countDown();
            try {
                await(releaseChildren);
            } finally {
                active.decrementAndGet();
            }
        });
    }

    /// Creates an inert parent whose dependents are the supplied siblings.
    private static Task<Void> parentTask(TaskResource resource, @Unmodifiable List<Task<?>> dependents) {
        return new Task<Void>() {
            /// Performs no primary work after both dependents complete.
            @Override
            public void execute() {
            }

            /// Returns the immutable sibling collection.
            @Override
            public @Unmodifiable List<Task<?>> getDependents() {
                return dependents;
            }
        }.setResources(resource);
    }

    /// Executes one task asynchronously with an isolated manager.
    private static CompletableFuture<Boolean> execute(Task<?> task, TaskResourceLockManager manager) {
        AsyncTaskExecutor executor = new AsyncTaskExecutor(task, manager);
        return CompletableFuture.supplyAsync(executor::test);
    }

    /// Verifies one terminal path releases its resource by running a conflicting successor afterward.
    private void assertTerminalPathReleases(String fileName, Task<?> terminalTask, boolean expectedSuccess) {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource resource = target(fileName);
        terminalTask.setResources(resource);
        AsyncTaskExecutor executor = new AsyncTaskExecutor(terminalTask, manager);

        assertEquals(expectedSuccess, assertTimeoutPreemptively(TIMEOUT, executor::test));
        assertSuccessorRuns(manager, resource);
    }

    /// Verifies a conflicting successor can acquire the resource and that bookkeeping is then empty.
    private static void assertSuccessorRuns(TaskResourceLockManager manager, TaskResource resource) {
        AtomicBoolean successorRan = new AtomicBoolean();
        Task<?> successor = task(resource, () -> successorRan.set(true));

        assertTrue(assertTimeoutPreemptively(TIMEOUT, () -> new AsyncTaskExecutor(successor, manager).test()));
        assertTrue(successorRan.get());
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Returns one normalized download-target resource beneath the temporary directory.
    private TaskResource target(String fileName) {
        return TaskResource.downloadTarget(temporaryDirectory.resolve(fileName));
    }

    /// Waits for a latch and propagates timeout as a test failure.
    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for test coordination");
    }

    /// Waits within a bounded assertion until an observable asynchronous condition becomes true.
    private static void awaitCondition(BooleanSupplier condition) {
        assertTimeoutPreemptively(TIMEOUT, () -> {
            while (!condition.getAsBoolean()) {
                Thread.yield();
            }
        });
    }

    /// Returns a bounded asynchronous boolean result.
    private static boolean get(CompletableFuture<Boolean> result) {
        return assertTimeoutPreemptively(TIMEOUT, result::join);
    }
}
