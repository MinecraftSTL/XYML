/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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

import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.Lang;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static space.minecraftstl.xyml.util.Lang.*;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Executes a task graph asynchronously while publishing lifecycle events and cooperative cancellation state.
@NotNullByDefault
public final class AsyncTaskExecutor extends TaskExecutor {
    /// The terminal execution future, or null before [#start()] finishes constructing the asynchronous chain.
    private @Nullable CompletableFuture<Boolean> future;

    /// Whether [#start()] has begun and cancellation requests are therefore valid.
    private volatile boolean started;

    /// Shared or test-injected semantic resource manager.
    private final TaskResourceLockManager resourceLockManager;

    /// Live cancellation domains belonging to repeated starts of this executor.
    private final Set<TaskResourceLockManager.Execution> resourceExecutions = ConcurrentHashMap.newKeySet();

    /// Creates an asynchronous executor rooted at the supplied task.
    public AsyncTaskExecutor(Task<?> task) {
        this(task, TaskResourceLockManager.SHARED);
    }

    /// Creates an asynchronous executor with an isolated resource manager for package tests.
    ///
    /// @param task root task
    /// @param resourceLockManager manager coordinating resources for this executor
    AsyncTaskExecutor(Task<?> task, TaskResourceLockManager resourceLockManager) {
        super(task);
        this.resourceLockManager = Objects.requireNonNull(resourceLockManager, "resourceLockManager");
    }

    /// Starts one execution chain and returns this executor.
    ///
    /// The started flag is published before any listener notification so synchronous listener cancellation is valid.
    /// Every constructed chain attempts exactly one terminal stop notification, including exceptional [Error] paths.
    /// Repeated calls retain the historical behavior of starting another chain and replacing [#future].
    @Override
    public TaskExecutor start() {
        exception = null;
        failure = null;
        started = true;
        TaskResourceLockManager.Execution resourceExecution = resourceLockManager.createExecution();
        resourceExecutions.add(resourceExecution);
        try {
            notifyTaskListeners(TaskListener::onStart);
        } catch (RuntimeException | Error failure) {
            resourceExecutions.remove(resourceExecution);
            throw failure;
        }
        future = executeTasks(null, null, resourceExecution, Collections.singleton(firstTask))
                .handleAsync((@Nullable Exception exception, @Nullable Throwable throwable) -> {
                    boolean success = exception == null && throwable == null;
                    try {
                        if (throwable != null) {
                            Throwable resolvedFailure = resolveException(throwable);
                            failure = resolvedFailure;
                            Lang.handleUncaughtException(resolvedFailure);
                        } else {
                            failure = exception;
                            if (exception != null) {
                                // We log exception stacktrace because some exceptions indicate launcher defects.
                                LOG.warning("An exception occurred in task execution", exception);

                                Throwable resolvedException = resolveException(exception);
                                if (resolvedException instanceof RuntimeException &&
                                        !(resolvedException instanceof CancellationException) &&
                                        !(resolvedException instanceof JsonParseException) &&
                                        !(resolvedException instanceof RejectedExecutionException)) {
                                    // Track unexpected RuntimeException without classifying known user failures.
                                    @Nullable Thread.UncaughtExceptionHandler handler = uncaughtExceptionHandler;
                                    if (handler != null)
                                        handler.uncaughtException(
                                                Thread.currentThread(), resolvedException);
                                }
                            }
                        }
                    } finally {
                        notifyTaskListeners(it -> it.onStop(success, this));
                    }

                    return success;
                })
                .exceptionally(e -> {
                    Throwable resolved = resolveException(e);
                    if (resolved instanceof OutOfMemoryError) {
                        notifyTaskListeners(it -> it.onStop(false, this));
                    }
                    Lang.handleUncaughtException(resolved);
                    return false;
                })
                .whenComplete((@Nullable Boolean success, @Nullable Throwable throwable) ->
                        resourceExecutions.remove(resourceExecution));
        return this;
    }

    /// Starts the chain, waits for its terminal future, and returns whether it succeeded.
    @Override
    public boolean test() {
        start();
        try {
            CompletableFuture<Boolean> terminalFuture = Objects.requireNonNull(
                    future, "start() must create a terminal future before returning");
            return terminalFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignore) {
            // We have dealt with ExecutionException in exception handling and uncaught exception handler.
        } catch (CancellationException e) {
            LOG.info("Task " + firstTask + " has been cancelled.", e);
        }
        return false;
    }

    /// Records a cooperative cancellation request after execution has started.
    @Override
    public synchronized void cancel() {
        if (!started) {
            throw new IllegalStateException("Cannot cancel a not started TaskExecutor");
        }

        cancelled = true;
        for (TaskResourceLockManager.Execution resourceExecution : resourceExecutions) {
            resourceLockManager.cancel(resourceExecution);
        }
    }

    /// Executes a possibly absent collection of sibling tasks and completes exceptionally when any sibling fails.
    private CompletableFuture<@Nullable Void> executeTasksExceptionally(
            @Nullable Task<?> parentTask,
            @Nullable TaskResourceLockManager.Owner parentOwner,
            TaskResourceLockManager.Execution resourceExecution,
            @Nullable Collection<? extends Task<?>> tasks) {
        if (tasks == null || tasks.isEmpty())
            return CompletableFuture.<@Nullable Void>completedFuture(null);

        return CompletableFuture.<@Nullable Void>completedFuture(null)
                .thenComposeAsync((@Nullable Void unused) -> {
                    boolean hasTerminalCleanup = tasks.stream().anyMatch(Task::isTerminalCleanup);
                    if (isCancelled() && !hasTerminalCleanup) {
                        for (Task<?> task : tasks) task.setException(new CancellationException());
                        return CompletableFuture.runAsync(this::checkCancellation);
                    }

                    return CompletableFuture.allOf(tasks.stream()
                            .map(task -> {
                                TaskResourceLockManager.Execution taskExecution = task.isTerminalCleanup()
                                        ? resourceLockManager.createExecution()
                                        : resourceExecution;
                                return CompletableFuture.<@Nullable Void>completedFuture(null)
                                        .thenComposeAsync((@Nullable Void unused2) -> executeTask(
                                                parentTask,
                                                parentOwner,
                                                taskExecution,
                                                task));
                            })
                            .toArray(CompletableFuture<?>[]::new));
                });
    }

    /// Executes sibling tasks and converts their terminal failure to a nullable future value.
    private CompletableFuture<@Nullable Exception> executeTasks(
            @Nullable Task<?> parentTask,
            @Nullable TaskResourceLockManager.Owner parentOwner,
            TaskResourceLockManager.Execution resourceExecution,
            Collection<? extends Task<?>> tasks) {
        return executeTasksExceptionally(parentTask, parentOwner, resourceExecution, tasks)
                .thenApplyAsync((@Nullable Void unused) -> (Exception) null)
                .exceptionally(throwable -> {
                    Throwable resolved = resolveException(throwable);
                    if (resolved instanceof Exception) {
                        return (Exception) resolved;
                    } else {
                        // If an error occurred, we just rethrow it.
                        throw new CompletionException(throwable);
                    }
                });
    }

    /// Executes a task whose body supplies its own possibly nullable completable-future result.
    private <T> CompletableFuture<@Nullable T> executeCompletableFutureTask(
            @Nullable Task<?> parentTask,
            @Nullable TaskResourceLockManager.Owner parentOwner,
            TaskResourceLockManager.Execution resourceExecution,
            CompletableFutureTask<T> task) {
        LeaseReference leaseReference = new LeaseReference();
        CompletableFuture<@Nullable T> execution;
        try {
            TaskResourceLockManager.Owner owner = resourceLockManager.createOwner(
                    resourceExecution,
                    parentOwner,
                    task.getDeclaredResources(),
                    false);
            CompletableFuture<TaskResourceLockManager.Lease> acquisition = resourceLockManager.acquire(owner);
            leaseReference.trackPending(acquisition);
            execution = acquisition.thenCompose(lease -> {
                leaseReference.set(lease);
                return executeCompletableFutureTaskLifecycle(parentTask, owner, resourceExecution, task);
            });
        } catch (Throwable failure) {
            execution = CompletableFuture.failedFuture(failure);
        }

        return withLeaseRelease(handleCompletableFutureTaskCompletion(task, execution), leaseReference);
    }

    /// Runs the established future-task lifecycle after its semantic resources have been acquired.
    private <T> CompletableFuture<@Nullable T> executeCompletableFutureTaskLifecycle(
            @Nullable Task<?> parentTask,
            TaskResourceLockManager.Owner owner,
            TaskResourceLockManager.Execution resourceExecution,
            CompletableFutureTask<T> task) {
        return CompletableFuture.<@Nullable Void>completedFuture(null)
                .thenComposeAsync((@Nullable Void unused) -> {
                    checkCancellation(task);

                    task.setCancelled(this::isCancelled);
                    task.setState(Task.TaskState.READY);
                    if (parentTask != null && task.getStage() == null)
                        task.setStage(parentTask.getStage());

                    if (task.getSignificance().shouldLog())
                        LOG.trace("Executing task: " + task.getName());

                    notifyTaskListeners(it -> it.onReady(task));

                    NestedTaskScope scope = new NestedTaskScope(task, owner, resourceExecution);
                    CompletableFuture<@Nullable T> mainFuture;
                    try {
                        mainFuture = Objects.requireNonNull(
                                task.getFuture(scope),
                                "CompletableFutureTask.getFuture() returned null");
                    } catch (Throwable failure) {
                        mainFuture = CompletableFuture.failedFuture(failure);
                    }
                    return scope.closeAfter(mainFuture);
                })
                .thenApplyAsync((@Nullable T result) -> {
                    checkCancellation(task);

                    if (task.getSignificance().shouldLog()) {
                        LOG.trace("Task finished: " + task.getName());
                    }

                    task.setResult(result);
                    task.fireDoneEvent(this, false);
                    notifyTaskListeners(it -> it.onFinished(task));

                    task.setState(Task.TaskState.SUCCEEDED);

                    return result;
                });
    }

    /// Applies the established future-task failure classification before the resource lease is released.
    private <T> CompletableFuture<@Nullable T> handleCompletableFutureTaskCompletion(
            CompletableFutureTask<T> task,
            CompletableFuture<@Nullable T> execution) {
        return execution.exceptionally(throwable -> {
                    Throwable resolved = resolveException(throwable);
                    if (resolved instanceof Exception e) {
                        if (e instanceof InterruptedException || e instanceof CancellationException) {
                            task.setException(null);
                            if (task.getSignificance().shouldLog()) {
                                LOG.trace("Task aborted: " + task.getName());
                            }
                            task.fireDoneEvent(this, true);
                            notifyTaskListeners(it -> it.onFailed(task, e));
                        } else {
                            task.setException(e);
                            exception = e;
                            if (task.getSignificance().shouldLog()) {
                                LOG.trace("Task failed: " + task.getName(), e);
                            }
                            task.fireDoneEvent(this, true);
                            notifyTaskListeners(it -> it.onFailed(task, e));
                        }

                        task.setState(Task.TaskState.FAILED);
                    } else if (resolved instanceof OutOfMemoryError e) {
                        handleOutOfMemoryError(task, e);
                    }

                    throw new CompletionException(resolved); // rethrow error
                });
    }

    /// Executes a regular task while holding its semantic resources through terminal listener notification unless it
    /// explicitly hands the lease to its prerequisites or dependencies.
    private <T> CompletableFuture<@Nullable T> executeNormalTask(
            @Nullable Task<?> parentTask,
            @Nullable TaskResourceLockManager.Owner parentOwner,
            TaskResourceLockManager.Execution resourceExecution,
            Task<T> task) {
        LeaseReference leaseReference = new LeaseReference();
        @Nullable TaskResourceLockManager.Owner ownerForCompletion = null;
        CompletableFuture<@Nullable T> execution;
        try {
            TaskResourceLockManager.Owner owner = resourceLockManager.createOwner(
                    resourceExecution,
                    parentOwner,
                    task.getDeclaredResources(),
                    task.releasesResourcesBeforeDependencies() || task.releasesResourcesBeforeDependents());
            ownerForCompletion = owner;
            CompletableFuture<TaskResourceLockManager.Lease> acquisition = resourceLockManager.acquire(owner);
            leaseReference.trackPending(acquisition);
            execution = acquisition.thenCompose(lease -> {
                leaseReference.set(lease);
                return executeNormalTaskLifecycle(parentTask, owner, resourceExecution, task, leaseReference);
            });
        } catch (Throwable failure) {
            execution = CompletableFuture.failedFuture(failure);
        }

        return withLeaseRelease(handleNormalTaskCompletion(task, ownerForCompletion, execution, leaseReference),
                leaseReference);
    }

    /// Runs the established regular-task lifecycle after its semantic resources have been acquired.
    private <T> CompletableFuture<@Nullable T> executeNormalTaskLifecycle(
            @Nullable Task<?> parentTask,
            TaskResourceLockManager.Owner owner,
            TaskResourceLockManager.Execution resourceExecution,
            Task<T> task,
            LeaseReference leaseReference) {
        // A handed-off owner remains in the invocation ancestry after its lease is released. The manager starts child
        // coverage at the nearest active ancestor, so a later descendant cannot revive the handed-off resource.
        TaskResourceLockManager.Owner dependencyOwner = owner;
        return CompletableFuture.<@Nullable Void>completedFuture(null)
                .thenComposeAsync((@Nullable Void unused) -> {
                    checkCancellation(task);

                    task.setCancelled(this::isCancelled);
                    task.setState(Task.TaskState.READY);
                    if (task.getStage() != null) {
                        task.setInheritedStage(task.getStage());
                    } else if (parentTask != null) {
                        task.setInheritedStage(parentTask.getInheritedStage());
                    }
                    task.setNotifyPropertiesChanged(() ->
                            notifyTaskListeners(task, it -> it.onPropertiesUpdate(task)));

                    if (task.getSignificance().shouldLog())
                        LOG.trace("Executing task: " + task.getName());

                    notifyTaskListeners(task, it -> it.onReady(task));

                    if (task.doPreExecute()) {
                        return CompletableFuture.runAsync(wrap(task::preExecute), task.getExecutor());
                    } else {
                        return CompletableFuture.<@Nullable Void>completedFuture(null);
                    }
                })
                .thenApplyAsync((@Nullable Void unused) -> {
                    if (task.releasesResourcesBeforeDependents()) {
                        leaseReference.releaseForHandoff();
                    }
                    return unused;
                })
                .thenComposeAsync((@Nullable Void unused) -> executeTasks(
                        task,
                        owner,
                        resourceExecution,
                        task.getDependents()))
                .thenComposeAsync((@Nullable Exception dependentsException) -> {
                    boolean isDependentsSucceeded = dependentsException == null;

                    if (isDependentsSucceeded) {
                        task.setDependentsSucceeded();
                    } else {
                        task.setException(dependentsException);

                        if (task.isRelyingOnDependents()) {
                            rethrow(dependentsException);
                        }
                    }

                    NestedTaskScope scope = new NestedTaskScope(task, owner, resourceExecution);
                    CompletableFuture<@Nullable Void> mainExecution = CompletableFuture.runAsync(wrap(() -> {
                        task.setState(Task.TaskState.RUNNING);
                        notifyTaskListeners(task, it -> it.onRunning(task));
                        task.execute(scope);
                    }), task.getExecutor()).thenApply((@Nullable Void unused) -> (Void) null);
                    return scope.closeAfter(mainExecution).whenComplete(
                            (@Nullable Void unused, @Nullable Throwable throwable) -> {
                        task.setState(Task.TaskState.EXECUTED);
                        if (throwable == null && task.releasesResourcesBeforeDependencies()) {
                            leaseReference.releaseForHandoff();
                        }
                        rethrow(throwable);
                    });
                })
                .thenComposeAsync((@Nullable Void unused) -> executeTasks(
                        task,
                        dependencyOwner,
                        resourceExecution,
                        task.getDependencies()))
                .thenComposeAsync((@Nullable Exception dependenciesException) -> {
                    boolean isDependenciesSucceeded = dependenciesException == null;

                    if (isDependenciesSucceeded)
                        task.setDependenciesSucceeded();

                    return reacquireTerminalLease(owner, leaseReference)
                            .thenComposeAsync((@Nullable Void unused) -> {
                                if (task.doPostExecute()) {
                                    return CompletableFuture.runAsync(wrap(task::postExecute), task.getExecutor())
                                            .thenApply((@Nullable Void ignored) -> dependenciesException);
                                } else {
                                    return CompletableFuture.completedFuture(dependenciesException);
                                }
                            });
                })
                .thenApplyAsync((@Nullable Exception dependenciesException) -> {
                    boolean isDependenciesSucceeded = dependenciesException == null;

                    if (!isDependenciesSucceeded) {
                        LOG.error("Subtasks failed for " + task.getName());
                        task.setException(dependenciesException);
                        if (task.isRelyingOnDependencies()) {
                            rethrow(dependenciesException);
                        }
                    }

                    checkCancellation(task);

                    if (task.getSignificance().shouldLog()) {
                        LOG.trace("Task finished: " + task.getName());
                    }

                    task.fireDoneEvent(this, false);
                    notifyTaskListeners(task, it -> it.onFinished(task));

                    task.setState(Task.TaskState.SUCCEEDED);
                    return task.getResult();
                });
    }

    /// Applies the established regular-task failure classification before the resource lease is released.
    private <T> CompletableFuture<@Nullable T> handleNormalTaskCompletion(
            Task<T> task,
            @Nullable TaskResourceLockManager.Owner owner,
            CompletableFuture<@Nullable T> execution,
            LeaseReference leaseReference) {
        return execution.handle((@Nullable T result, @Nullable Throwable throwable) -> {
            if (throwable == null) {
                return CompletableFuture.completedFuture(result);
            }
            return reacquireTerminalLease(owner, leaseReference)
                    .handle((@Nullable Void ignored, @Nullable Throwable reacquisitionFailure) -> {
                        if (reacquisitionFailure != null) {
                            attachReacquisitionFailure(throwable, reacquisitionFailure);
                        }
                        return classifyNormalTaskFailure(task, throwable);
                    })
                    .thenCompose(stage -> stage);
        }).thenCompose(stage -> stage);
    }

    /// Classifies one established regular-task failure and republishes its historical terminal callbacks.
    private <T> CompletableFuture<@Nullable T> classifyNormalTaskFailure(Task<T> task, Throwable throwable) {
        Throwable resolved = resolveException(throwable);
        if (resolved instanceof Exception) {
            Exception e = convertInterruptedException((Exception) resolved);
            task.setException(e);
            exception = e;
            if (e instanceof CancellationException) {
                if (task.getSignificance().shouldLog()) {
                    LOG.trace("Task aborted: " + task.getName());
                }
            } else {
                if (task.getSignificance().shouldLog()) {
                    LOG.trace("Task failed: " + task.getName(), e);
                }
            }
            task.fireDoneEvent(this, true);
            notifyTaskListeners(task, it -> it.onFailed(task, e));

            task.setState(Task.TaskState.FAILED);
        } else if (resolved instanceof OutOfMemoryError e) {
            handleOutOfMemoryError(task, e);
        }

        return CompletableFuture.failedFuture(new CompletionException(resolved));
    }

    /// Retains a terminal reacquisition failure without replacing the original task failure.
    private static void attachReacquisitionFailure(Throwable original, Throwable reacquisitionFailure) {
        Throwable resolved = resolveException(reacquisitionFailure);
        if (resolved != original) {
            original.addSuppressed(resolved);
        }
    }

    /// Reacquires a handed-off owner's resources before post-execution and terminal listener delivery.
    ///
    /// The manager request is asynchronous and uses the same logical owner token, so a continuation may cross threads
    /// without losing reentrancy. An owner that never received its initial lease has no committed lifecycle to protect.
    ///
    /// @param owner owner whose resources were handed off, or null when owner creation failed
    /// @param leaseReference lease state for this task invocation
    /// @return future completed after the terminal lease is held
    private CompletableFuture<@Nullable Void> reacquireTerminalLease(
            @Nullable TaskResourceLockManager.Owner owner,
            LeaseReference leaseReference) {
        if (owner == null || !leaseReference.needsTerminalReacquisition()) {
            return CompletableFuture.completedFuture(null);
        }
        return leaseReference.reacquire(resourceLockManager, owner);
    }

    /// Completes the failed task lifecycle while preserving the original error for the global handler.
    ///
    /// @param task task whose terminal callbacks must be published
    /// @param error original out-of-memory error
    private void handleOutOfMemoryError(Task<?> task, OutOfMemoryError error) {
        Exception taskException = new Exception(error);
        task.setException(taskException);
        exception = taskException;
        task.fireDoneEvent(this, true);
        notifyTaskListeners(task, it -> it.onFailed(task, error));
        task.setState(Task.TaskState.FAILED);
    }

    /// Publishes one task lifecycle event unless the task is an implementation-only terminal cleanup child.
    ///
    /// @param task task associated with the event
    /// @param action listener callback to publish
    private void notifyTaskListeners(Task<?> task, Consumer<? super TaskListener> action) {
        if (!task.isTerminalCleanup()) {
            notifyTaskListeners(action);
        }
    }

    /// Dispatches one task to the regular or completable-future execution path.
    private <T> CompletableFuture<@Nullable T> executeTask(
            @Nullable Task<?> parentTask,
            @Nullable TaskResourceLockManager.Owner parentOwner,
            TaskResourceLockManager.Execution resourceExecution,
            Task<T> task) {
        if (task instanceof CompletableFutureTask<T> completableFutureTask) {
            return executeCompletableFutureTask(parentTask, parentOwner, resourceExecution, completableFutureTask);
        } else {
            return executeNormalTask(parentTask, parentOwner, resourceExecution, task);
        }
    }

    /// Attaches an internal lease-release watcher while shielding it from cancellation of the returned future.
    ///
    /// [CompletableFuture#copy()] creates a dependent future whose cancellation does not cancel the source stage. The
    /// source therefore still reaches the watcher when a caller abandons a nested task future before its lease is
    /// granted. The lease reference itself also handles a grant racing with an earlier release.
    private static <T> CompletableFuture<@Nullable T> withLeaseRelease(
            CompletableFuture<@Nullable T> terminal,
            LeaseReference leaseReference) {
        CompletableFuture<@Nullable T> releaseStage = terminal.whenComplete(
                (@Nullable T result, @Nullable Throwable throwable) -> leaseReference.release());
        return releaseStage.copy();
    }

    /// Tracks every child source started by one executor-managed task invocation.
    ///
    /// The scope is kept outside [Task] so reused task objects cannot share owner state. Returned child futures are
    /// copies; cancellation or manual completion by business code therefore cannot suppress the internal source used
    /// for parent completion and lease release.
    @NotNullByDefault
    private final class NestedTaskScope implements TaskCompletableFuture {
        /// Parent task whose structured lifetime includes every registered child source.
        private final Task<?> parentTask;

        /// Invocation-local owner inherited by registered child tasks.
        private final TaskResourceLockManager.Owner parentOwner;

        /// Cancellation domain shared with registered child tasks.
        private final TaskResourceLockManager.Execution resourceExecution;

        /// Internal child sources retained until the parent main future closes this scope.
        private final List<CompletableFuture<?>> childSources = new ArrayList<>();

        /// Whether the main future reached its terminal state and registration is therefore closed.
        private boolean closed;

        /// Creates one open structured child scope.
        ///
        /// @param parentTask parent task
        /// @param parentOwner invocation-local parent owner
        /// @param resourceExecution shared cancellation domain
        private NestedTaskScope(
                Task<?> parentTask,
                TaskResourceLockManager.Owner parentOwner,
                TaskResourceLockManager.Execution resourceExecution) {
            this.parentTask = parentTask;
            this.parentOwner = parentOwner;
            this.resourceExecution = resourceExecution;
        }

        /// Starts one registered child and returns an isolated future view.
        ///
        /// @param subtask child task
        /// @param <T> possibly nullable child result type
        /// @return independently cancellable child future view
        @Override
        public synchronized <T> CompletableFuture<@Nullable T> one(Task<T> subtask) {
            ensureOpen();
            CompletableFuture<@Nullable T> source = executeTask(
                    parentTask,
                    parentOwner,
                    resourceExecution,
                    Objects.requireNonNull(subtask, "subtask"));
            childSources.add(source);
            return source.copy();
        }

        /// Starts one registered sibling group from an immutable collection snapshot.
        ///
        /// @param tasks child tasks
        /// @return independently cancellable aggregate future view
        @Override
        public synchronized CompletableFuture<@Nullable Void> all(@Unmodifiable Collection<? extends Task<?>> tasks) {
            ensureOpen();
            @Unmodifiable List<Task<?>> taskSnapshot = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
            CompletableFuture<@Nullable Void> source = executeTasksExceptionally(
                    parentTask,
                    parentOwner,
                    resourceExecution,
                    taskSnapshot);
            childSources.add(source);
            return source.copy();
        }

        /// Closes registration after the main task body or future and then waits for every registered internal source.
        ///
        /// The main future remains the sole source of the parent's result and failure. A child future already reports
        /// its own failure through its task lifecycle and the view returned by [#one(Task)] or [#all(Collection)]; a
        /// caller that deliberately discards that view retains the historical exception-propagation behavior. The
        /// scope changes only the parent's resource lifetime.
        ///
        /// @param mainFuture future returned by the completable-future task
        /// @param <T> possibly nullable parent result type
        /// @return future preserving the parent result after every child source terminates
        private <T> CompletableFuture<@Nullable T> closeAfter(CompletableFuture<@Nullable T> mainFuture) {
            return mainFuture.handle((@Nullable T result, @Nullable Throwable mainFailure) ->
                    closeAndAwaitChildren().thenApply((@Nullable Void unused) -> {
                        if (mainFailure instanceof CompletionException completionFailure) {
                            throw completionFailure;
                        }
                        if (mainFailure != null) {
                            throw new CompletionException(mainFailure);
                        }
                        return result;
                    }))
                    .thenCompose(nested -> nested);
        }

        /// Atomically closes registration and returns a future completed after every child source terminates.
        ///
        /// @return future completed normally after all children, regardless of their individual outcomes
        private CompletableFuture<@Nullable Void> closeAndAwaitChildren() {
            @Unmodifiable List<CompletableFuture<?>> sourceSnapshot;
            synchronized (this) {
                closed = true;
                sourceSnapshot = List.copyOf(childSources);
            }
            CompletableFuture<?>[] terminalSources = sourceSnapshot.stream()
                    .map(source -> source.handle((@Nullable Object result, @Nullable Throwable failure) -> null))
                    .toArray(CompletableFuture<?>[]::new);
            return CompletableFuture.allOf(terminalSources);
        }

        /// Rejects child registration after the structured parent scope has closed.
        ///
        /// @throws IllegalStateException when registration is closed
        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("Task child scope is already closed");
            }
        }

    }

    /// Owns one task lease lifecycle, including an optional handoff and terminal reacquisition.
    @NotNullByDefault
    private static final class LeaseReference {
        /// Leases currently held by this task invocation.
        private final List<TaskResourceLockManager.Lease> leases = new ArrayList<>();

        /// Acquisition currently being resolved, or null after it reaches a terminal state.
        private @Nullable CompletableFuture<TaskResourceLockManager.Lease> pendingAcquisition;

        /// Shared terminal reacquisition future, created at most once after a handoff.
        private @Nullable CompletableFuture<@Nullable Void> terminalAcquisition;

        /// Whether an early handoff has released a previously granted lease.
        private boolean handedOff;

        /// Whether the task's complete lease lifecycle has reached its terminal release point.
        private boolean released;

        /// Installs a newly granted lease or closes it immediately when terminal release already won the race.
        ///
        /// @param newLease newly granted lease
        /// @return whether the lease was retained by this reference
        private boolean set(TaskResourceLockManager.Lease newLease) {
            Objects.requireNonNull(newLease, "newLease");
            boolean closeImmediately;
            synchronized (this) {
                closeImmediately = released;
                if (!closeImmediately) {
                    leases.add(newLease);
                }
            }
            if (closeImmediately) {
                newLease.close();
            }
            return !closeImmediately;
        }

        /// Tracks an asynchronous acquisition so terminal release can cancel a still-pending waiter.
        ///
        /// @param acquisition acquisition future to track
        private void trackPending(CompletableFuture<TaskResourceLockManager.Lease> acquisition) {
            Objects.requireNonNull(acquisition, "acquisition");
            boolean cancelImmediately;
            synchronized (this) {
                cancelImmediately = released;
                if (!cancelImmediately) {
                    if (pendingAcquisition != null && !pendingAcquisition.isDone()) {
                        throw new IllegalStateException("Task lease acquisition was already pending");
                    }
                    pendingAcquisition = acquisition;
                }
            }
            acquisition.whenComplete((@Nullable TaskResourceLockManager.Lease ignoredLease,
                    @Nullable Throwable ignoredFailure) -> {
                synchronized (this) {
                    if (pendingAcquisition == acquisition) {
                        pendingAcquisition = null;
                    }
                }
            });
            if (cancelImmediately) {
                acquisition.cancel(false);
            }
        }

        /// Releases the currently held lease during an explicit dependency or dependent handoff.
        private void releaseForHandoff() {
            @Unmodifiable List<TaskResourceLockManager.Lease> leasesToClose;
            synchronized (this) {
                if (released) {
                    return;
                }
                handedOff = true;
                leasesToClose = List.copyOf(leases);
                leases.clear();
            }
            for (TaskResourceLockManager.Lease lease : leasesToClose) {
                lease.close();
            }
        }

        /// Returns whether this invocation released a lease that must be reacquired for terminal callbacks.
        private synchronized boolean needsTerminalReacquisition() {
            return handedOff && !released;
        }

        /// Asynchronously reacquires the same owner resources once after an early handoff.
        ///
        /// @param manager resource manager coordinating the request
        /// @param owner logical owner whose canonical request is reused
        /// @return future completed after the terminal lease is held
        private CompletableFuture<@Nullable Void> reacquire(
                TaskResourceLockManager manager,
                TaskResourceLockManager.Owner owner) {
            CompletableFuture<@Nullable Void> result;
            synchronized (this) {
                if (!handedOff || released) {
                    return CompletableFuture.completedFuture(null);
                }
                if (terminalAcquisition != null) {
                    return terminalAcquisition;
                }
                result = new CompletableFuture<>();
                terminalAcquisition = result;
            }

            CompletableFuture<TaskResourceLockManager.Lease> acquisition;
            try {
                acquisition = manager.acquireForTerminal(owner);
                try {
                    trackPending(acquisition);
                } catch (Throwable trackingFailure) {
                    acquisition.cancel(false);
                    throw trackingFailure;
                }
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
                return result;
            }
            acquisition.whenComplete((@Nullable TaskResourceLockManager.Lease lease,
                    @Nullable Throwable failure) -> {
                if (failure != null) {
                    result.completeExceptionally(failure);
                    return;
                }
                try {
                    if (set(Objects.requireNonNull(lease, "terminal lease"))) {
                        result.complete(null);
                    } else {
                        result.cancel(false);
                    }
                } catch (Throwable installationFailure) {
                    result.completeExceptionally(installationFailure);
                }
            });
            return result;
        }

        /// Marks this reference terminally released and closes every installed lease outside the reference monitor.
        private void release() {
            @Unmodifiable List<TaskResourceLockManager.Lease> leasesToClose;
            @Nullable CompletableFuture<TaskResourceLockManager.Lease> acquisitionToCancel;
            @Nullable CompletableFuture<@Nullable Void> terminalToCancel;
            synchronized (this) {
                if (released) {
                    return;
                }
                released = true;
                leasesToClose = List.copyOf(leases);
                leases.clear();
                acquisitionToCancel = pendingAcquisition;
                pendingAcquisition = null;
                terminalToCancel = terminalAcquisition;
            }
            if (acquisitionToCancel != null) {
                acquisitionToCancel.cancel(false);
            }
            if (terminalToCancel != null && !terminalToCancel.isDone()) {
                terminalToCancel.cancel(false);
            }
            for (TaskResourceLockManager.Lease lease : leasesToClose) {
                lease.close();
            }
        }
    }

    /// Throws a cancellation exception when cooperative cancellation has been requested.
    private void checkCancellation() {
        if (isCancelled()) {
            throw new CancellationException("Cancelled by user");
        }
    }

    /// Throws a cancellation exception unless an already-committed terminal cleanup must still run.
    ///
    /// @param task task about to cross a cancellation checkpoint
    private void checkCancellation(Task<?> task) {
        if (!task.isTerminalCleanup()) {
            checkCancellation();
        }
    }

    /// Converts interruption to cancellation while returning every other exception unchanged.
    private static Exception convertInterruptedException(Exception e) {
        if (e instanceof InterruptedException) {
            return new CancellationException(e.getMessage());
        } else {
            return e;
        }
    }

    /// Optional handler for unexpected runtime failures attributed to launcher defects.
    private static @Nullable Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

    /// Replaces or clears the handler for unexpected runtime failures.
    public static void setUncaughtExceptionHandler(@Nullable Thread.UncaughtExceptionHandler uncaughtExceptionHandler) {
        AsyncTaskExecutor.uncaughtExceptionHandler = uncaughtExceptionHandler;
    }
}
