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
import space.minecraftstl.xyml.util.Lang;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

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
                    if (isCancelled()) {
                        for (Task<?> task : tasks) task.setException(new CancellationException());
                        return CompletableFuture.runAsync(this::checkCancellation);
                    }

                    return CompletableFuture.allOf(tasks.stream()
                            .map(task -> CompletableFuture.<@Nullable Void>completedFuture(null)
                                    .thenComposeAsync((@Nullable Void unused2) -> executeTask(
                                            parentTask,
                                            parentOwner,
                                            resourceExecution,
                                            task))
                            ).toArray(CompletableFuture<?>[]::new));
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
        AtomicReference<TaskResourceLockManager.@Nullable Lease> leaseReference = new AtomicReference<>();
        CompletableFuture<@Nullable T> execution;
        try {
            TaskResourceLockManager.Owner owner = resourceLockManager.createOwner(
                    resourceExecution,
                    parentOwner,
                    task.getResources());
            execution = resourceLockManager.acquire(owner).thenCompose(lease -> {
                leaseReference.set(lease);
                return executeCompletableFutureTaskLifecycle(parentTask, owner, resourceExecution, task);
            });
        } catch (Throwable failure) {
            execution = CompletableFuture.failedFuture(failure);
        }

        return handleCompletableFutureTaskCompletion(task, execution)
                .whenComplete((@Nullable T result, @Nullable Throwable throwable) -> release(leaseReference));
    }

    /// Runs the established future-task lifecycle after its semantic resources have been acquired.
    private <T> CompletableFuture<@Nullable T> executeCompletableFutureTaskLifecycle(
            @Nullable Task<?> parentTask,
            TaskResourceLockManager.Owner owner,
            TaskResourceLockManager.Execution resourceExecution,
            CompletableFutureTask<T> task) {
        return CompletableFuture.<@Nullable Void>completedFuture(null)
                .thenComposeAsync((@Nullable Void unused) -> {
                    checkCancellation();

                    task.setCancelled(this::isCancelled);
                    task.setState(Task.TaskState.READY);
                    if (parentTask != null && task.getStage() == null)
                        task.setStage(parentTask.getStage());

                    if (task.getSignificance().shouldLog())
                        LOG.trace("Executing task: " + task.getName());

                    notifyTaskListeners(it -> it.onReady(task));

                    return task.getFuture(new TaskCompletableFuture() {
                        /// Executes one nested task with the current task as its parent.
                        @Override
                        public <T2> CompletableFuture<@Nullable T2> one(Task<T2> subtask) {
                            return executeTask(task, owner, resourceExecution, subtask);
                        }

                        /// Executes all supplied nested tasks with the current task as their parent.
                        @Override
                        public CompletableFuture<@Nullable Void> all(Collection<Task<?>> tasks) {
                            return executeTasksExceptionally(task, owner, resourceExecution, tasks);
                        }
                    });
                })
                .thenApplyAsync((@Nullable T result) -> {
                    checkCancellation();

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
    /// explicitly hands the lease to its dependencies.
    private <T> CompletableFuture<@Nullable T> executeNormalTask(
            @Nullable Task<?> parentTask,
            @Nullable TaskResourceLockManager.Owner parentOwner,
            TaskResourceLockManager.Execution resourceExecution,
            Task<T> task) {
        AtomicReference<TaskResourceLockManager.@Nullable Lease> leaseReference = new AtomicReference<>();
        CompletableFuture<@Nullable T> execution;
        try {
            TaskResourceLockManager.Owner owner = resourceLockManager.createOwner(
                    resourceExecution,
                    parentOwner,
                    task.getResources());
            execution = resourceLockManager.acquire(owner).thenCompose(lease -> {
                leaseReference.set(lease);
                return executeNormalTaskLifecycle(parentTask, owner, resourceExecution, task, leaseReference);
            });
        } catch (Throwable failure) {
            execution = CompletableFuture.failedFuture(failure);
        }

        return handleNormalTaskCompletion(task, execution)
                .whenComplete((@Nullable T result, @Nullable Throwable throwable) -> release(leaseReference));
    }

    /// Runs the established regular-task lifecycle after its semantic resources have been acquired.
    private <T> CompletableFuture<@Nullable T> executeNormalTaskLifecycle(
            @Nullable Task<?> parentTask,
            TaskResourceLockManager.Owner owner,
            TaskResourceLockManager.Execution resourceExecution,
            Task<T> task,
            AtomicReference<TaskResourceLockManager.@Nullable Lease> leaseReference) {
        TaskResourceLockManager.@Nullable Owner dependencyOwner = task.releasesResourcesBeforeDependencies()
                ? null
                : owner;
        return CompletableFuture.<@Nullable Void>completedFuture(null)
                .thenComposeAsync((@Nullable Void unused) -> {
                    checkCancellation();

                    task.setCancelled(this::isCancelled);
                    task.setState(Task.TaskState.READY);
                    if (task.getStage() != null) {
                        task.setInheritedStage(task.getStage());
                    } else if (parentTask != null) {
                        task.setInheritedStage(parentTask.getInheritedStage());
                    }
                    task.setNotifyPropertiesChanged(() ->
                            notifyTaskListeners(it -> it.onPropertiesUpdate(task)));

                    if (task.getSignificance().shouldLog())
                        LOG.trace("Executing task: " + task.getName());

                    notifyTaskListeners(it -> it.onReady(task));

                    if (task.doPreExecute()) {
                        return CompletableFuture.runAsync(wrap(task::preExecute), task.getExecutor());
                    } else {
                        return CompletableFuture.<@Nullable Void>completedFuture(null);
                    }
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

                    return CompletableFuture.runAsync(wrap(() -> {
                        task.setState(Task.TaskState.RUNNING);
                        notifyTaskListeners(it -> it.onRunning(task));
                        task.execute();
                    }), task.getExecutor()).whenComplete(
                            (@Nullable Void unused, @Nullable Throwable throwable) -> {
                        task.setState(Task.TaskState.EXECUTED);
                        if (throwable == null && task.releasesResourcesBeforeDependencies()) {
                            release(leaseReference);
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

                    if (task.doPostExecute()) {
                        return CompletableFuture.runAsync(wrap(task::postExecute), task.getExecutor())
                                .thenApply((@Nullable Void unused) -> dependenciesException);
                    } else {
                        return CompletableFuture.completedFuture(dependenciesException);
                    }
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

                    checkCancellation();

                    if (task.getSignificance().shouldLog()) {
                        LOG.trace("Task finished: " + task.getName());
                    }

                    task.fireDoneEvent(this, false);
                    notifyTaskListeners(it -> it.onFinished(task));

                    task.setState(Task.TaskState.SUCCEEDED);
                    return task.getResult();
                });
    }

    /// Applies the established regular-task failure classification before the resource lease is released.
    private <T> CompletableFuture<@Nullable T> handleNormalTaskCompletion(
            Task<T> task,
            CompletableFuture<@Nullable T> execution) {
        return execution.exceptionally(throwable -> {
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
                notifyTaskListeners(it -> it.onFailed(task, e));

                task.setState(Task.TaskState.FAILED);
            } else if (resolved instanceof OutOfMemoryError e) {
                handleOutOfMemoryError(task, e);
            }

            throw new CompletionException(resolved); // rethrow error
        });
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
        notifyTaskListeners(it -> it.onFailed(task, error));
        task.setState(Task.TaskState.FAILED);
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

    /// Releases the acquired lease at most once after every task-specific terminal path has finished.
    private static void release(AtomicReference<TaskResourceLockManager.@Nullable Lease> leaseReference) {
        @Nullable TaskResourceLockManager.Lease lease = leaseReference.getAndSet(null);
        if (lease != null) {
            lease.close();
        }
    }

    /// Throws a cancellation exception when cooperative cancellation has been requested.
    private void checkCancellation() {
        if (isCancelled()) {
            throw new CancellationException("Cancelled by user");
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
