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
import java.util.concurrent.*;

import static space.minecraftstl.xyml.util.Lang.*;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Executes a task graph asynchronously while publishing lifecycle events and cooperative cancellation state.
@NotNullByDefault
public final class AsyncTaskExecutor extends TaskExecutor {
    /// The terminal execution future, or null before [#start()] finishes constructing the asynchronous chain.
    private @Nullable CompletableFuture<Boolean> future;

    /// Whether [#start()] has begun and cancellation requests are therefore valid.
    private volatile boolean started;

    /// Creates an asynchronous executor rooted at the supplied task.
    public AsyncTaskExecutor(Task<?> task) {
        super(task);
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
        notifyTaskListeners(TaskListener::onStart);
        future = executeTasks(null, Collections.singleton(firstTask))
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
                    Lang.handleUncaughtException(resolveException(e));
                    return false;
                });
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
    }

    /// Executes a possibly absent collection of sibling tasks and completes exceptionally when any sibling fails.
    private CompletableFuture<@Nullable Void> executeTasksExceptionally(
            @Nullable Task<?> parentTask, @Nullable Collection<? extends Task<?>> tasks) {
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
                                    .thenComposeAsync((@Nullable Void unused2) -> executeTask(parentTask, task))
                            ).toArray(CompletableFuture<?>[]::new));
                });
    }

    /// Executes sibling tasks and converts their terminal failure to a nullable future value.
    private CompletableFuture<@Nullable Exception> executeTasks(
            @Nullable Task<?> parentTask, Collection<? extends Task<?>> tasks) {
        return executeTasksExceptionally(parentTask, tasks)
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
            @Nullable Task<?> parentTask, CompletableFutureTask<T> task) {
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
                            return executeTask(task, subtask);
                        }

                        /// Executes all supplied nested tasks with the current task as their parent.
                        @Override
                        public CompletableFuture<@Nullable Void> all(Collection<Task<?>> tasks) {
                            return executeTasksExceptionally(task, tasks);
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
                })
                .exceptionally(throwable -> {
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
                    }

                    throw new CompletionException(resolved); // rethrow error
                });
    }

    /// Executes a regular task through pre-work, prerequisites, body, follow-ups, and post-work.
    private <T> CompletableFuture<@Nullable T> executeNormalTask(@Nullable Task<?> parentTask, Task<T> task) {
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
                .thenComposeAsync((@Nullable Void unused) -> executeTasks(task, task.getDependents()))
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
                        rethrow(throwable);
                    });
                })
                .thenComposeAsync((@Nullable Void unused) -> executeTasks(task, task.getDependencies()))
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
                })
                .exceptionally(throwable -> {
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
                    }

                    throw new CompletionException(resolved); // rethrow error
                });
    }

    /// Dispatches one task to the regular or completable-future execution path.
    private <T> CompletableFuture<@Nullable T> executeTask(@Nullable Task<?> parentTask, Task<T> task) {
        if (task instanceof CompletableFutureTask<T> completableFutureTask) {
            return executeCompletableFutureTask(parentTask, completableFutureTask);
        } else {
            return executeNormalTask(parentTask, task);
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
