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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /// Verifies one instance owner remains held while its metadata phase hands off to the operation phase.
    @Test
    public void resourceHandoffRetainsSameInstanceUntilOperationCompletes() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource repositoryResource = TaskResource.repositoryMetadata(temporaryDirectory.resolve("repository"));
        TaskResource operationResource = TaskResource.repositoryOperation(temporaryDirectory.resolve("repository"));
        TaskResource instanceResource = TaskResource.gameInstance(
                temporaryDirectory.resolve("repository/versions/example"));
        CountDownLatch firstResolutionStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstResolution = new CountDownLatch(1);
        CountDownLatch secondResolutionStarted = new CountDownLatch(1);
        CountDownLatch firstOperationStarted = new CountDownLatch(1);
        CountDownLatch secondOperationStarted = new CountDownLatch(1);
        CountDownLatch releaseOperations = new CountDownLatch(1);

        Task<?> first = handoffTask(
                repositoryResource,
                operationResource,
                instanceResource,
                firstResolutionStarted,
                releaseFirstResolution,
                firstOperationStarted,
                releaseOperations);
        Task<?> second = handoffTask(
                repositoryResource,
                operationResource,
                instanceResource,
                secondResolutionStarted,
                new CountDownLatch(0),
                secondOperationStarted,
                releaseOperations);

        CompletableFuture<Boolean> firstResult = execute(first, manager);
        assertTrue(firstResolutionStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Boolean> secondResult = execute(second, manager);
        awaitCondition(() -> manager.pendingWaiterCount() == 1);
        assertFalse(secondResolutionStarted.await(200, TimeUnit.MILLISECONDS));

        releaseFirstResolution.countDown();
        assertTrue(firstOperationStarted.await(5, TimeUnit.SECONDS));
        assertFalse(secondResolutionStarted.await(200, TimeUnit.MILLISECONDS));

        releaseOperations.countDown();
        assertTrue(secondResolutionStarted.await(5, TimeUnit.SECONDS));
        assertTrue(secondOperationStarted.await(5, TimeUnit.SECONDS));
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

    /// Verifies a pure all-of root hands off before its prerequisite and does not hold a global lease over the wait.
    @Test
    public void allOfRootHandsOffBeforePrerequisites() throws Exception {
        TaskResource firstResource = target("aggregate-first.jar");
        TaskResource unrelatedResource = target("aggregate-unrelated.jar");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch unrelatedStarted = new CountDownLatch(1);

        Task<?> first = task(firstResource, () -> {
            firstStarted.countDown();
            await(releaseFirst);
        });
        Task<?> aggregate = Task.allOf(List.of(first));
        Task<?> unrelated = task(unrelatedResource, unrelatedStarted::countDown);
        TaskResourceLockManager manager = new TaskResourceLockManager();

        CompletableFuture<Boolean> aggregateResult = execute(aggregate, manager);
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Boolean> unrelatedResult = execute(unrelated, manager);

        assertTrue(unrelatedStarted.await(5, TimeUnit.SECONDS));
        releaseFirst.countDown();
        assertTrue(get(aggregateResult));
        assertTrue(get(unrelatedResult));
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies that a repository-scoped resolution phase serializes while its detached instance operations overlap.
    @Test
    public void resourceHandoffSerializesResolutionAndParallelizesInstances() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource repositoryResource = TaskResource.repositoryMetadata(temporaryDirectory.resolve("repository"));
        TaskResource operationResource = TaskResource.repositoryOperation(temporaryDirectory.resolve("repository"));
        TaskResource firstInstance = TaskResource.gameInstance(temporaryDirectory.resolve("repository/versions/first"));
        TaskResource secondInstance = TaskResource.gameInstance(temporaryDirectory.resolve("repository/versions/second"));
        CountDownLatch firstResolutionStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstResolution = new CountDownLatch(1);
        CountDownLatch secondResolutionStarted = new CountDownLatch(1);
        CountDownLatch releaseSecondResolution = new CountDownLatch(1);
        CountDownLatch firstOperationStarted = new CountDownLatch(1);
        CountDownLatch secondOperationStarted = new CountDownLatch(1);
        CountDownLatch releaseOperations = new CountDownLatch(1);

        Task<?> first = handoffTask(
                repositoryResource,
                operationResource,
                firstInstance,
                firstResolutionStarted,
                releaseFirstResolution,
                firstOperationStarted,
                releaseOperations);
        Task<?> second = handoffTask(
                repositoryResource,
                operationResource,
                secondInstance,
                secondResolutionStarted,
                releaseSecondResolution,
                secondOperationStarted,
                releaseOperations);

        CompletableFuture<Boolean> firstResult = execute(first, manager);
        assertTrue(firstResolutionStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Boolean> secondResult = execute(second, manager);
        awaitCondition(() -> manager.pendingWaiterCount() == 1);
        assertFalse(secondResolutionStarted.await(200, TimeUnit.MILLISECONDS));

        releaseFirstResolution.countDown();
        assertTrue(secondResolutionStarted.await(5, TimeUnit.SECONDS));
        assertTrue(firstOperationStarted.await(5, TimeUnit.SECONDS));
        assertFalse(secondOperationStarted.await(200, TimeUnit.MILLISECONDS));

        releaseSecondResolution.countDown();
        assertTrue(secondOperationStarted.await(5, TimeUnit.SECONDS));

        releaseOperations.countDown();
        assertTrue(get(firstResult));
        assertTrue(get(secondResult));
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies parallel instance operations can release their shared scope before serialized repository finalizers.
    @Test
    public void resourceAwareFinalizersCanUpgradeOnlyAfterOperationHandoff() throws Exception {
        Path repository = temporaryDirectory.resolve("finalizer-handoff-repository");
        TaskResource operationResource = TaskResource.repositoryOperation(repository);
        TaskResource repositoryResource = TaskResource.gameDirectory(repository);
        CountDownLatch operationsOverlapped = new CountDownLatch(1);
        CyclicBarrier operationBarrier = new CyclicBarrier(2, operationsOverlapped::countDown);
        CountDownLatch releaseOperations = new CountDownLatch(1);
        CountDownLatch firstFinalizerStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstFinalizer = new CountDownLatch(1);
        AtomicInteger activeFinalizers = new AtomicInteger();
        AtomicInteger maximumActiveFinalizers = new AtomicInteger();
        AtomicInteger completedFinalizers = new AtomicInteger();
        TaskResourceLockManager manager = new TaskResourceLockManager();

        Task<?> first = finalizingHandoffTask(
                operationResource,
                TaskResource.gameInstance(repository.resolve("versions/first")),
                repositoryResource,
                operationBarrier,
                releaseOperations,
                firstFinalizerStarted,
                releaseFirstFinalizer,
                activeFinalizers,
                maximumActiveFinalizers,
                completedFinalizers);
        Task<?> second = finalizingHandoffTask(
                operationResource,
                TaskResource.gameInstance(repository.resolve("versions/second")),
                repositoryResource,
                operationBarrier,
                releaseOperations,
                firstFinalizerStarted,
                releaseFirstFinalizer,
                activeFinalizers,
                maximumActiveFinalizers,
                completedFinalizers);

        CompletableFuture<Boolean> firstResult = execute(first, manager);
        CompletableFuture<Boolean> secondResult = execute(second, manager);
        assertTrue(operationsOverlapped.await(5, TimeUnit.SECONDS));
        releaseOperations.countDown();
        assertTrue(firstFinalizerStarted.await(5, TimeUnit.SECONDS));
        awaitCondition(() -> manager.pendingWaiterCount() == 1);
        assertEquals(1, activeFinalizers.get());

        releaseFirstFinalizer.countDown();
        assertTrue(get(firstResult));
        assertTrue(get(secondResult));
        assertEquals(2, completedFinalizers.get());
        assertEquals(1, maximumActiveFinalizers.get());
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies audited dynamic composition callbacks do not globalize different instance branches.
    @Test
    public void auditedDynamicCompositionParallelizesDifferentInstances() {
        Path repository = temporaryDirectory.resolve("dynamic-repository");
        CyclicBarrier barrier = new CyclicBarrier(2);
        TaskResourceLockManager manager = new TaskResourceLockManager();
        Task<?> first = dynamicCompositionParent(repository, "first", barrier);
        Task<?> second = dynamicCompositionParent(repository, "second", barrier);

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

    /// Verifies a handed-off task reacquires its resource before post-execution work can run.
    @Test
    public void handedOffTaskReacquiresResourceBeforePostExecute() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource resource = target("handoff-post.jar");
        TaskResource dependencyResource = target("handoff-post-dependency.jar");
        CountDownLatch dependencyStarted = new CountDownLatch(1);
        CountDownLatch releaseDependency = new CountDownLatch(1);
        CountDownLatch competitorStarted = new CountDownLatch(1);
        CountDownLatch releaseCompetitor = new CountDownLatch(1);
        AtomicBoolean postStarted = new AtomicBoolean();

        Task<?> dependency = task(dependencyResource, () -> {
            dependencyStarted.countDown();
            await(releaseDependency);
        });
        Task<Void> handedOff = new Task<Void>() {
            /// Returns the independent dependency that runs after this task hands off its resource.
            @Override
            public @Unmodifiable List<Task<?>> getDependencies() {
                return List.of(dependency);
            }

            /// Performs no primary operation before the dependency phase.
            @Override
            public void execute() {
            }

            /// Enables the post-execution callback whose resource ownership is being checked.
            @Override
            public boolean doPostExecute() {
                return true;
            }

            /// Records that terminal work entered while the resource was held again.
            @Override
            public void postExecute() {
                postStarted.set(true);
            }
        }.setResources(resource).releaseResourcesBeforeDependencies();

        CompletableFuture<Boolean> handedOffResult = execute(handedOff, manager);
        assertTrue(dependencyStarted.await(5, TimeUnit.SECONDS));

        Task<?> competitor = task(resource, () -> {
            competitorStarted.countDown();
            await(releaseCompetitor);
        });
        CompletableFuture<Boolean> competitorResult = execute(competitor, manager);
        assertTrue(competitorStarted.await(5, TimeUnit.SECONDS));

        releaseDependency.countDown();
        awaitCondition(() -> manager.pendingWaiterCount() >= 1);
        assertFalse(postStarted.get());

        releaseCompetitor.countDown();
        assertTrue(get(handedOffResult));
        assertTrue(get(competitorResult));
        assertTrue(postStarted.get());
        assertEquals(0, manager.pendingWaiterCount());
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

    /// Verifies cancelling a completable-future task while waiting removes its waiter without invoking its body.
    @Test
    public void cancellingWaitingCompletableFutureTaskSkipsFutureBody() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource resource = target("future-waiting-cancel.jar");
        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        AtomicBoolean futureBodyStarted = new AtomicBoolean();
        Task<?> holder = task(resource, () -> {
            holderStarted.countDown();
            await(releaseHolder);
        });
        CompletableFutureTask<@Nullable Void> waitingTask = new CompletableFutureTask<>() {
            /// Records unexpected future-body execution after the waiting task is cancelled.
            @Override
            public CompletableFuture<@Nullable Void> getFuture(TaskCompletableFuture executor) {
                futureBodyStarted.set(true);
                return CompletableFuture.completedFuture(null);
            }
        };
        waitingTask.setResources(resource);

        CompletableFuture<Boolean> holderResult = execute(holder, manager);
        assertTrue(holderStarted.await(5, TimeUnit.SECONDS));
        AsyncTaskExecutor waitingExecutor = new AsyncTaskExecutor(waitingTask, manager);
        CompletableFuture<Boolean> waitingResult = CompletableFuture.supplyAsync(waitingExecutor::test);
        awaitCondition(() -> manager.pendingWaiterCount() == 1);

        waitingExecutor.cancel();
        assertFalse(get(waitingResult));
        assertFalse(futureBodyStarted.get());
        assertEquals(0, manager.pendingWaiterCount());

        releaseHolder.countDown();
        assertTrue(get(holderResult));
        assertSuccessorRuns(manager, resource);
    }

    /// Verifies a future task can launch a child from another continuation thread under the same owner chain.
    @Test
    public void completableFutureChildReentersAcrossThreads() {
        TaskResource instance = TaskResource.gameInstance(temporaryDirectory.resolve("instances/future-parent"));
        AtomicBoolean childRan = new AtomicBoolean();
        Task<@Nullable Void> child = task(
                TaskResource.downloadTarget(temporaryDirectory.resolve("instances/future-parent/child.jar")),
                () -> childRan.set(true));
        CompletableFutureTask<@Nullable Void> parent = new CompletableFutureTask<>() {
            /// Schedules the child through a separate completable-future continuation.
            @Override
            public CompletableFuture<@Nullable Void> getFuture(TaskCompletableFuture executor) {
                return CompletableFuture.completedFuture(null)
                        .thenComposeAsync(ignored -> executor.one(child));
            }
        };
        parent.setResources(instance);
        Task<?> parentTask = parent;
        TaskResourceLockManager manager = new TaskResourceLockManager();

        assertTrue(assertTimeoutPreemptively(TIMEOUT, () -> new AsyncTaskExecutor(parentTask, manager).test()));
        assertTrue(childRan.get());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies an ordinary task can register a dynamic child from another worker without losing parent ownership.
    @Test
    public void regularTaskContextReentersAcrossThreadsAndDelaysCompletion() throws Exception {
        Path instanceDirectory = temporaryDirectory.resolve("instances/regular-parent");
        TaskResource instance = TaskResource.gameInstance(instanceDirectory);
        TaskResource childResource = TaskResource.downloadTarget(instanceDirectory.resolve("child.jar"));
        CountDownLatch childRegistered = new CountDownLatch(1);
        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch releaseChild = new CountDownLatch(1);
        AtomicBoolean competitorRan = new AtomicBoolean();
        Task<?> child = task(childResource, () -> {
            childStarted.countDown();
            await(releaseChild);
        });
        Task<Void> parent = new Task<Void>() {
            /// Retains synchronous [Task#run()] compatibility without creating dynamic children.
            @Override
            public void execute() {
            }

            /// Registers the child from a different continuation worker before this primary operation returns.
            @Override
            public void execute(TaskExecutionContext context) throws Exception {
                CompletableFuture.runAsync(() -> {
                    context.one(child);
                    childRegistered.countDown();
                }).get(5, TimeUnit.SECONDS);
            }
        }.setResources(instance);
        TaskResourceLockManager manager = new TaskResourceLockManager();

        CompletableFuture<Boolean> parentResult = execute(parent, manager);
        assertTrue(childRegistered.await(5, TimeUnit.SECONDS));
        assertTrue(childStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Boolean> competitorResult = execute(task(childResource, () -> competitorRan.set(true)), manager);
        awaitCondition(() -> manager.pendingWaiterCount() == 1);

        assertFalse(parentResult.isDone());
        assertFalse(competitorRan.get());
        releaseChild.countDown();

        assertTrue(get(parentResult));
        assertTrue(get(competitorResult));
        assertTrue(competitorRan.get());
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a normal task cannot retain its invocation-local child context after its primary body has returned.
    @Test
    public void regularTaskContextClosesAfterPrimaryExecution() {
        AtomicReference<TaskExecutionContext> contextReference = new AtomicReference<>();
        Task<Void> parent = new Task<Void>() {
            /// Retains direct-run compatibility without a dynamic child context.
            @Override
            public void execute() {
            }

            /// Captures the executor-owned context only for this invocation.
            @Override
            public void execute(TaskExecutionContext context) {
                contextReference.set(context);
            }
        }.setResources(target("closed-context.jar"));

        assertTrue(new AsyncTaskExecutor(parent, new TaskResourceLockManager()).test());

        TaskExecutionContext context = Objects.requireNonNull(contextReference.get(), "task execution context");
        assertThrows(IllegalStateException.class, () -> context.one(Task.completed(null)));
    }

    /// Verifies cancelling an exposed nested future cannot suppress cleanup when its lease is granted later.
    @Test
    public void cancelledNestedFutureCannotLeakLateLease() throws Exception {
        TaskResource resource = target("late-cancel.jar");
        TaskResource parentResource = TaskResource.repositoryOperation(temporaryDirectory);
        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        CountDownLatch childScheduled = new CountDownLatch(1);
        CountDownLatch childRan = new CountDownLatch(1);
        AtomicReference<CompletableFuture<@Nullable Void>> childFuture = new AtomicReference<>();
        CompletableFuture<@Nullable Void> parentCompletion = new CompletableFuture<>();
        Task<?> holder = task(resource, () -> {
            holderStarted.countDown();
            await(releaseHolder);
        });
        Task<@Nullable Void> child = task(resource, childRan::countDown);
        CompletableFutureTask<@Nullable Void> parent = new CompletableFutureTask<>() {
            /// Schedules one conflicting child and keeps the parent alive until the test releases it.
            @Override
            public CompletableFuture<@Nullable Void> getFuture(TaskCompletableFuture executor) {
                childFuture.set(executor.one(child));
                childScheduled.countDown();
                return parentCompletion;
            }
        };
        parent.setResources(parentResource);
        TaskResourceLockManager manager = new TaskResourceLockManager();
        CompletableFuture<Boolean> holderResult = execute(holder, manager);
        assertTrue(holderStarted.await(5, TimeUnit.SECONDS));
        AsyncTaskExecutor parentExecutor = new AsyncTaskExecutor(parent, manager);
        CompletableFuture<Boolean> parentResult = CompletableFuture.supplyAsync(parentExecutor::test);
        assertTrue(childScheduled.await(5, TimeUnit.SECONDS));
        awaitCondition(() -> manager.pendingWaiterCount() == 1);

        CompletableFuture<@Nullable Void> exposedChildFuture = Objects.requireNonNull(childFuture.get());
        assertTrue(exposedChildFuture.cancel(false));
        releaseHolder.countDown();
        assertTrue(get(holderResult));
        assertTrue(childRan.await(5, TimeUnit.SECONDS));

        AtomicBoolean successorRan = new AtomicBoolean();
        assertTrue(get(execute(task(resource, () -> successorRan.set(true)), manager)));
        assertTrue(successorRan.get());
        assertTrue(exposedChildFuture.isCancelled());
        parentCompletion.complete(null);
        assertTrue(get(parentResult));
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a discarded sibling-group view remains in the parent lifetime until every internal source terminates.
    @Test
    public void discardedCompletableFutureChildrenDelayParentCompletion() throws Exception {
        Path instanceDirectory = temporaryDirectory.resolve("instances/discarded-children");
        TaskResource instance = TaskResource.gameInstance(instanceDirectory);
        CountDownLatch blockingChildStarted = new CountDownLatch(1);
        CountDownLatch releaseBlockingChild = new CountDownLatch(1);
        CountDownLatch siblingFinished = new CountDownLatch(1);
        AtomicBoolean competitorRan = new AtomicBoolean();
        Task<?> blockingChild = task(
                TaskResource.downloadTarget(instanceDirectory.resolve("blocking.jar")),
                () -> {
                    blockingChildStarted.countDown();
                    await(releaseBlockingChild);
                });
        Task<?> sibling = task(
                TaskResource.downloadTarget(instanceDirectory.resolve("sibling.jar")),
                siblingFinished::countDown);
        CompletableFutureTask<@Nullable Void> parent = new CompletableFutureTask<>() {
            /// Starts both children but deliberately discards the returned aggregate view.
            @Override
            public CompletableFuture<@Nullable Void> getFuture(TaskCompletableFuture executor) {
                executor.all(List.of(blockingChild, sibling));
                return CompletableFuture.completedFuture(null);
            }
        };
        parent.setResources(instance);
        TaskResourceLockManager manager = new TaskResourceLockManager();

        CompletableFuture<Boolean> parentResult = execute(parent, manager);
        assertTrue(blockingChildStarted.await(5, TimeUnit.SECONDS));
        assertTrue(siblingFinished.await(5, TimeUnit.SECONDS));
        CompletableFuture<Boolean> competitorResult = execute(task(
                TaskResource.downloadTarget(instanceDirectory.resolve("competitor.jar")),
                () -> competitorRan.set(true)), manager);
        awaitCondition(() -> manager.pendingWaiterCount() == 1);

        assertFalse(parentResult.isDone());
        assertFalse(competitorRan.get());
        releaseBlockingChild.countDown();

        assertTrue(get(parentResult));
        assertTrue(get(competitorResult));
        assertTrue(competitorRan.get());
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies discarded-child terminal failures retain historical propagation while delaying resource release.
    @Test
    public void discardedCompletableFutureChildFailuresDoNotReplaceMainOutcome() {
        assertDiscardedChildFailureDoesNotReplaceMainOutcome(
                "discarded-exception.jar",
                Task.runAsync(() -> {
                    throw new IOException("discarded child exception");
                }));
        assertDiscardedChildFailureDoesNotReplaceMainOutcome(
                "discarded-error.jar",
                Task.runAsync(() -> {
                    throw new AssertionError("discarded child error");
                }));
        assertDiscardedChildFailureDoesNotReplaceMainOutcome(
                "discarded-rejected.jar",
                Task.runAsync(command -> {
                    throw new RejectedExecutionException("discarded child rejection");
                }, () -> {
                }));
        CompletableFuture<@Nullable Void> cancelledFuture = new CompletableFuture<>();
        assertTrue(cancelledFuture.cancel(false));
        assertDiscardedChildFailureDoesNotReplaceMainOutcome(
                "discarded-cancelled.jar",
                Task.fromCompletableFuture(cancelledFuture));
    }

    /// Verifies a discarded child cannot modify the main future's original failure object.
    @Test
    public void mainCompletableFutureFailureRemainsPrimary() {
        TaskResource resource = target("structured-main-failure.jar");
        IOException mainFailure = new IOException("main future failure");
        AssertionError childFailure = new AssertionError("discarded child failure");
        Task<?> child = task(resource, () -> {
            throw childFailure;
        });
        CompletableFutureTask<@Nullable Void> parent = new CompletableFutureTask<>() {
            /// Starts one discarded failing child before returning an independently failed main future.
            @Override
            public CompletableFuture<@Nullable Void> getFuture(TaskCompletableFuture executor) {
                executor.one(child);
                return CompletableFuture.failedFuture(mainFailure);
            }
        };
        parent.setResources(resource);
        TaskResourceLockManager manager = new TaskResourceLockManager();
        AsyncTaskExecutor executor = new AsyncTaskExecutor(parent, manager);

        assertFalse(assertTimeoutPreemptively(TIMEOUT, executor::test));
        assertSame(mainFailure, executor.getFailure());
        assertEquals(0, mainFailure.getSuppressed().length);
        assertSuccessorRuns(manager, resource);
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

    /// Verifies a cancelled future reaches both legacy and independently resourced completion callbacks.
    @Test
    public void cancelledCompletableFutureReachesCompletionCallbacks() {
        AtomicReference<@Nullable Exception> legacyFailure = new AtomicReference<>();
        CompletableFuture<@Nullable Void> legacyFuture = new CompletableFuture<>();
        legacyFuture.cancel(false);
        Task<?> legacyCompletion = Task.fromCompletableFuture(legacyFuture)
                .setResources(target("legacy-cancelled-future.jar"))
                .whenComplete(Runnable::run, legacyFailure::set);
        AsyncTaskExecutor legacyExecutor = new AsyncTaskExecutor(legacyCompletion, new TaskResourceLockManager());

        assertFalse(assertTimeoutPreemptively(TIMEOUT, legacyExecutor::test));
        assertTrue(legacyFailure.get() instanceof java.util.concurrent.CancellationException);

        Path repository = temporaryDirectory.resolve("resourced-cancelled-future");
        TaskResourceLockManager manager = new TaskResourceLockManager();
        AtomicReference<@Nullable Exception> resourcedFailure = new AtomicReference<>();
        CompletableFuture<@Nullable Void> resourcedFuture = new CompletableFuture<>();
        resourcedFuture.cancel(false);
        Task<?> resourcedCompletion = Task.fromCompletableFuture(resourcedFuture)
                .setResources(
                        TaskResource.repositoryOperation(repository),
                        TaskResource.gameInstance(repository.resolve("versions/example")))
                .whenCompleteWithResources(
                        Runnable::run,
                        resourcedFailure::set,
                        TaskResource.repositoryMetadata(repository));
        AsyncTaskExecutor resourcedExecutor = new AsyncTaskExecutor(resourcedCompletion, manager);

        assertFalse(assertTimeoutPreemptively(TIMEOUT, resourcedExecutor::test));
        assertTrue(resourcedFailure.get() instanceof java.util.concurrent.CancellationException);
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
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

    /// Verifies a terminal cleanup does not acquire its repository resource while the long prerequisite is running.
    @Test
    public void resourceAwareCompletionAcquiresCleanupResourceAfterPrerequisite() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource operationResource = TaskResource.repositoryOperation(
                temporaryDirectory.resolve("cleanup-repository"));
        TaskResource cleanupResource = TaskResource.repositoryMetadata(temporaryDirectory.resolve("cleanup-repository"));
        TaskResource instanceResource = TaskResource.gameInstance(
                temporaryDirectory.resolve("cleanup-repository/versions/example"));
        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        CountDownLatch prerequisiteStarted = new CountDownLatch(1);
        CountDownLatch releasePrerequisite = new CountDownLatch(1);
        AtomicBoolean cleanupRan = new AtomicBoolean();
        Task<?> holder = task(cleanupResource, () -> {
            holderStarted.countDown();
            await(releaseHolder);
        });
        Task<?> prerequisite = Task.runAsync(() -> {
            prerequisiteStarted.countDown();
            await(releasePrerequisite);
        }).setResources(operationResource, instanceResource);
        Task<?> completion = prerequisite.whenCompleteWithResources(
                Runnable::run,
                ignoredFailure -> cleanupRan.set(true),
                cleanupResource);

        CompletableFuture<Boolean> holderResult = execute(holder, manager);
        assertTrue(holderStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Boolean> completionResult = execute(completion, manager);
        assertTrue(prerequisiteStarted.await(5, TimeUnit.SECONDS));
        assertEquals(0, manager.pendingWaiterCount());

        releasePrerequisite.countDown();
        awaitCondition(() -> manager.pendingWaiterCount() == 1);
        assertFalse(cleanupRan.get());
        releaseHolder.countDown();

        assertTrue(get(holderResult));
        assertTrue(get(completionResult));
        assertTrue(cleanupRan.get());
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a resource-aware completion never inherits either early-release policy from its prerequisite.
    @Test
    public void resourceAwareCompletionRetainsSourceResourceAcrossCleanup() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource resource = target("retained-cleanup.jar");
        CountDownLatch prerequisiteStarted = new CountDownLatch(1);
        CountDownLatch releasePrerequisite = new CountDownLatch(1);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        AtomicBoolean competitorRan = new AtomicBoolean();
        Task<?> prerequisite = Task.runAsync(() -> {
            prerequisiteStarted.countDown();
            await(releasePrerequisite);
        }).setResources(resource)
                .releaseResourcesBeforeDependents()
                .releaseResourcesBeforeDependencies();
        Task<?> completion = prerequisite.whenCompleteWithResources(
                Runnable::run,
                ignoredFailure -> {
                    cleanupStarted.countDown();
                    await(releaseCleanup);
                },
                resource);

        assertFalse(completion.releasesResourcesBeforeDependents());
        assertFalse(completion.releasesResourcesBeforeDependencies());
        CompletableFuture<Boolean> completionResult = execute(completion, manager);
        assertTrue(prerequisiteStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Boolean> competitorResult = execute(
                task(resource, () -> competitorRan.set(true)),
                manager);
        awaitCondition(() -> manager.pendingWaiterCount() == 1);
        assertFalse(competitorRan.get());

        releasePrerequisite.countDown();
        assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS));
        assertFalse(competitorRan.get());
        releaseCleanup.countDown();

        assertTrue(get(completionResult));
        assertTrue(get(competitorResult));
        assertTrue(competitorRan.get());
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a cleanup resource wider than its prerequisite is acquired after an automatic owner handoff.
    @Test
    public void resourceAwareCompletionHandsOffForWiderCleanupResource() throws Exception {
        TaskResource instanceResource = TaskResource.gameInstance(
                temporaryDirectory.resolve("handoff-cleanup/versions/example"));
        TaskResource repositoryResource = TaskResource.gameDirectory(
                temporaryDirectory.resolve("handoff-cleanup"));
        CountDownLatch prerequisiteStarted = new CountDownLatch(1);
        CountDownLatch releasePrerequisite = new CountDownLatch(1);
        CountDownLatch cleanupRan = new CountDownLatch(1);
        Task<?> prerequisite = Task.runAsync(() -> {
            prerequisiteStarted.countDown();
            await(releasePrerequisite);
        }).setResources(instanceResource);
        Task<?> completion = prerequisite.whenCompleteWithResources(
                Runnable::run,
                ignoredFailure -> cleanupRan.countDown(),
                repositoryResource);

        assertTrue(completion.releasesResourcesBeforeDependencies());
        AsyncTaskExecutor executor = new AsyncTaskExecutor(completion, new TaskResourceLockManager());
        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(executor::test);
        assertTrue(prerequisiteStarted.await(5, TimeUnit.SECONDS));
        releasePrerequisite.countDown();

        assertTrue(cleanupRan.await(5, TimeUnit.SECONDS));
        assertTrue(result.get(5, TimeUnit.SECONDS));
    }

    /// Verifies lexical containment alone cannot retain a cleanup lease before alias identity resolution.
    @Test
    public void resourceAwareCompletionHandsOffForNestedCleanupResource() {
        Path instance = temporaryDirectory.resolve("nested-cleanup/versions/example");
        Task<?> prerequisite = Task.runAsync(() -> {
        }).setResources(TaskResource.gameInstance(instance));

        Task<?> completion = prerequisite.whenCompleteWithResources(
                Runnable::run,
                ignoredFailure -> {
                },
                TaskResource.downloadTarget(instance.resolve(".xyml-installers/temporary.jar")));

        assertTrue(completion.releasesResourcesBeforeDependencies());
    }

    /// Verifies cancellation after finalizer startup still runs its independently resourced terminal cleanup.
    @Test
    public void resourceAwareCompletionRunsCleanupAfterCancellation() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource operationResource = TaskResource.repositoryOperation(temporaryDirectory.resolve("cancel-cleanup"));
        TaskResource instanceResource = TaskResource.gameInstance(
                temporaryDirectory.resolve("cancel-cleanup/versions/example"));
        TaskResource cleanupResource = TaskResource.repositoryMetadata(temporaryDirectory.resolve("cancel-cleanup"));
        CountDownLatch prerequisiteStarted = new CountDownLatch(1);
        CountDownLatch releasePrerequisite = new CountDownLatch(1);
        CountDownLatch cleanupRan = new CountDownLatch(1);
        AtomicReference<@Nullable Exception> cleanupFailure = new AtomicReference<>();
        Task<?> prerequisite = Task.runAsync(() -> {
            prerequisiteStarted.countDown();
            await(releasePrerequisite);
        }).setResources(operationResource, instanceResource);
        Task<?> completion = prerequisite.whenCompleteWithResources(
                Runnable::run,
                failure -> {
                    cleanupFailure.set(failure);
                    cleanupRan.countDown();
                },
                cleanupResource);
        AsyncTaskExecutor executor = new AsyncTaskExecutor(completion, manager);

        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(executor::test);
        assertTrue(prerequisiteStarted.await(5, TimeUnit.SECONDS));
        executor.cancel();
        releasePrerequisite.countDown();

        assertTrue(cleanupRan.await(5, TimeUnit.SECONDS));
        assertFalse(get(result));
        assertTrue(cleanupFailure.get() instanceof java.util.concurrent.CancellationException);
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a terminal-cleanup exception still takes precedence over the prerequisite failure.
    @Test
    public void resourceAwareCompletionPreservesCallbackFailurePriority() {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        Task<?> prerequisite = Task.runAsync(() -> {
            throw new IOException("prerequisite failure");
        }).setResources(
                TaskResource.repositoryOperation(temporaryDirectory),
                target("cleanup-prerequisite.jar"));
        Task<?> completion = prerequisite.whenCompleteWithResources(
                Runnable::run,
                ignoredFailure -> {
                    throw new IllegalStateException("cleanup failure");
                },
                target("cleanup-callback.jar"));
        AsyncTaskExecutor executor = new AsyncTaskExecutor(completion, manager);

        assertFalse(assertTimeoutPreemptively(TIMEOUT, executor::test));
        assertTrue(executor.getException() instanceof IllegalStateException);
        assertEquals("cleanup failure", Objects.requireNonNull(executor.getException()).getMessage());
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies the implementation-only cleanup child does not add externally visible lifecycle events.
    @Test
    public void resourceAwareCompletionPreservesVisibleListenerOrder() {
        Path repository = temporaryDirectory.resolve("cleanup-listeners");
        Task<?> prerequisite = Task.runAsync(() -> {
        }).setResources(
                TaskResource.repositoryOperation(repository),
                TaskResource.gameInstance(repository.resolve("versions/example")));
        Task<?> completion = prerequisite.whenCompleteWithResources(
                Runnable::run,
                ignoredFailure -> {
                },
                TaskResource.repositoryMetadata(repository));
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicBoolean unexpectedTask = new AtomicBoolean();
        AsyncTaskExecutor executor = new AsyncTaskExecutor(completion, new TaskResourceLockManager());
        executor.subscribeTaskListener(new TaskListener() {
            /// Records visible ready events and rejects an exposed cleanup child.
            @Override
            public void onReady(Task<?> task) {
                unexpectedTask.compareAndSet(false, task != prerequisite && task != completion);
                events.add(task == prerequisite ? "prerequisite-ready" : "completion-ready");
            }

            /// Records visible running events and rejects an exposed cleanup child.
            @Override
            public void onRunning(Task<?> task) {
                unexpectedTask.compareAndSet(false, task != prerequisite && task != completion);
                events.add(task == prerequisite ? "prerequisite-running" : "completion-running");
            }

            /// Records visible finished events and rejects an exposed cleanup child.
            @Override
            public void onFinished(Task<?> task) {
                unexpectedTask.compareAndSet(false, task != prerequisite && task != completion);
                events.add(task == prerequisite ? "prerequisite-finished" : "completion-finished");
            }

            /// Rejects any failed event because both visible tasks and the cleanup succeed.
            @Override
            public void onFailed(Task<?> task, Throwable throwable) {
                unexpectedTask.set(true);
            }
        });

        assertTrue(assertTimeoutPreemptively(TIMEOUT, executor::test));
        assertFalse(unexpectedTask.get());
        assertEquals(List.of(
                "completion-ready",
                "prerequisite-ready",
                "prerequisite-running",
                "prerequisite-finished",
                "completion-running",
                "completion-finished"), events);
    }

    /// Creates a regular task with one explicit resource declaration.
    private static Task<@Nullable Void> task(TaskResource resource, ExceptionalRunnable<?> action) {
        return Task.runAsync(action).setResources(resource);
    }

    /// Creates a two-phase task retaining its instance while handing off the short repository resolution resource.
    private static Task<@Nullable Void> handoffTask(
            TaskResource repositoryResource,
            TaskResource operationResource,
            TaskResource instanceResource,
            CountDownLatch resolutionStarted,
            CountDownLatch resolutionRelease,
            CountDownLatch operationStarted,
            CountDownLatch operationRelease) {
        Task<@Nullable Void> resolution = new Task<@Nullable Void>() {
            private List<Task<?>> dependencies = List.of();

            @Override
            public void execute() throws InterruptedException {
                resolutionStarted.countDown();
                resolutionRelease.await(5, TimeUnit.SECONDS);
                dependencies = List.of(Task.runAsync(() -> {
                    operationStarted.countDown();
                    await(operationRelease);
                }).setResources(operationResource, instanceResource));
            }

            @Override
            public List<Task<?>> getDependencies() {
                return dependencies;
            }
        }.setResources(repositoryResource, instanceResource).releaseResourcesBeforeDependencies();
        Task<@Nullable Void> operation = resolution.thenApplyAsync(ignored -> null);
        return operation.setResources(operationResource, instanceResource);
    }

    /// Creates an instance operation that hands off before one repository-wide terminal callback.
    ///
    /// @param operationResource shared repository-operation domain
    /// @param instanceResource exact instance boundary
    /// @param repositoryResource repository-wide finalizer boundary
    /// @param operationBarrier barrier proving both instance operations overlap
    /// @param releaseOperations latch releasing both operations
    /// @param firstFinalizerStarted latch signaled by the first finalizer
    /// @param releaseFirstFinalizer latch releasing serialized finalizers
    /// @param activeFinalizers current finalizer count
    /// @param maximumActiveFinalizers observed maximum finalizer count
    /// @param completedFinalizers completed finalizer count
    /// @return stopped resource-aware finalization chain
    private static Task<@Nullable Void> finalizingHandoffTask(
            TaskResource operationResource,
            TaskResource instanceResource,
            TaskResource repositoryResource,
            CyclicBarrier operationBarrier,
            CountDownLatch releaseOperations,
            CountDownLatch firstFinalizerStarted,
            CountDownLatch releaseFirstFinalizer,
            AtomicInteger activeFinalizers,
            AtomicInteger maximumActiveFinalizers,
            AtomicInteger completedFinalizers) {
        Task<@Nullable Void> operation = Task.runAsync(() -> {
            operationBarrier.await(5, TimeUnit.SECONDS);
            await(releaseOperations);
        }).setResources(operationResource, instanceResource);
        return operation.whenCompleteWithResources(Runnable::run, failure -> {
            if (failure != null) {
                throw failure;
            }
            int active = activeFinalizers.incrementAndGet();
            maximumActiveFinalizers.accumulateAndGet(active, Math::max);
            firstFinalizerStarted.countDown();
            try {
                await(releaseFirstFinalizer);
            } finally {
                activeFinalizers.decrementAndGet();
                completedFinalizers.incrementAndGet();
            }
        }, repositoryResource).asOrchestration();
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

    /// Creates one precise repository parent whose audited callback constructs an instance-local child.
    private static Task<Void> dynamicCompositionParent(Path repository, String instanceName, CyclicBarrier barrier) {
        TaskResource operation = TaskResource.repositoryOperation(repository);
        TaskResource instance = TaskResource.gameInstance(repository.resolve("versions").resolve(instanceName));
        Task<?> composition = Task.composeAsync(() -> task(instance, () -> barrier.await(5, TimeUnit.SECONDS)))
                .asOrchestration();
        return new Task<Void>() {
            /// Returns the audited dynamic composition as this parent's only prerequisite.
            @Override
            public @Unmodifiable List<Task<?>> getDependents() {
                return List.of(composition);
            }

            /// Performs no work after the instance-local branch completes.
            @Override
            public void execute() {
            }
        }.setResources(operation, instance);
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

    /// Verifies one discarded child failure leaves a successful main future unchanged and releases its lease.
    ///
    /// @param fileName unique resource name
    /// @param child discarded child task
    private void assertDiscardedChildFailureDoesNotReplaceMainOutcome(
            String fileName,
            Task<?> child) {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource resource = target(fileName);
        child.setResources(resource);
        CompletableFutureTask<@Nullable Void> parent = new CompletableFutureTask<>() {
            /// Starts one child while returning an independently successful main future.
            @Override
            public CompletableFuture<@Nullable Void> getFuture(TaskCompletableFuture executor) {
                executor.one(child);
                return CompletableFuture.completedFuture(null);
            }
        };
        parent.setResources(resource);
        AsyncTaskExecutor executor = new AsyncTaskExecutor(parent, manager);

        assertTrue(assertTimeoutPreemptively(TIMEOUT, executor::test));
        assertEquals(Task.TaskState.SUCCEEDED, parent.getState());
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
