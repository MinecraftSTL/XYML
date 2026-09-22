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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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

    /// Registry receiving one immutable top-level execution stream.
    private final TaskExecutionRegistry taskExecutionRegistry;

    /// Invocation-scoped progress handle bound only by the first top-level execution.
    private final TaskExecutionProgressHandle taskExecutionProgressHandle;

    /// Live cancellation domains belonging to repeated starts of this executor.
    private final Set<TaskResourceLockManager.Execution> resourceExecutions = ConcurrentHashMap.newKeySet();

    /// Invocation domains cancelled through the task-management page.
    private final Set<TaskResourceLockManager.Execution> cancelledExecutions = ConcurrentHashMap.newKeySet();

    /// Associates every invocation resource domain with its top-level monitoring handle.
    private final ConcurrentMap<TaskResourceLockManager.Execution, TaskExecutionRegistry.Execution>
            monitoredExecutions = new ConcurrentHashMap<>();

    /// Terminal-cleanup domains whose pending acquisition must survive executor cancellation.
    ///
    /// Terminal domains are kept separate from the ordinary execution set because the latter is also the source for
    /// cancellation propagation. Registration adds this marker before publishing the domain to
    /// [#resourceExecutions], so a concurrent cancellation can never observe an unclassified terminal domain.
    private final Set<TaskResourceLockManager.Execution> terminalResourceExecutions =
            ConcurrentHashMap.newKeySet();

    /// Creates an asynchronous executor rooted at the supplied task.
    public AsyncTaskExecutor(Task<?> task) {
        this(task, TaskResourceLockManager.SHARED, TaskExecutionRegistry.global());
    }

    /// Creates an asynchronous executor with an isolated resource manager for package tests.
    ///
    /// @param task root task
    /// @param resourceLockManager manager coordinating resources for this executor
    AsyncTaskExecutor(Task<?> task, TaskResourceLockManager resourceLockManager) {
        this(task, resourceLockManager, TaskExecutionRegistry.global());
    }

    /// Creates an executor with explicit resource and execution-registry ownership for isolated lifecycle tests.
    ///
    /// @param task root task
    /// @param resourceLockManager manager coordinating resources for this executor
    /// @param taskExecutionRegistry registry receiving this executor's top-level execution records
    AsyncTaskExecutor(
            Task<?> task,
            TaskResourceLockManager resourceLockManager,
            TaskExecutionRegistry taskExecutionRegistry) {
        super(task);
        this.resourceLockManager = Objects.requireNonNull(resourceLockManager, "resourceLockManager");
        this.taskExecutionRegistry = Objects.requireNonNull(taskExecutionRegistry, "taskExecutionRegistry");
        this.taskExecutionProgressHandle = TaskExecutionProgressHandle.forRegistry(taskExecutionRegistry);
    }

    /// Returns the invocation-scoped progress handle owned by this executor.
    ///
    /// @return handle bound to the first top-level execution only
    @Override
    public TaskExecutionProgressHandle taskExecutionProgressHandle() {
        return taskExecutionProgressHandle;
    }

    /// Starts one execution chain and returns this executor.
    ///
    /// The started flag is published before any listener notification so synchronous listener cancellation is valid.
    /// Every constructed chain attempts exactly one terminal stop notification, including exceptional [Error] paths.
    /// Repeated calls retain the historical behavior of starting another chain and replacing [#future].
    @Override
    public TaskExecutor start() {
        TaskResourceLockManager.Execution resourceExecution;
        TaskExecutionRegistry.Execution monitoredExecution;
        @Nullable Runnable cancellationToRun;
        synchronized (this) {
            exception = null;
            failure = null;
            // A terminal record may be retried with this executor instance. The resource domains below carry the
            // authoritative invocation-local cancellation state, so reset the legacy aggregate flag for the new run.
            cancelled = false;
            started = true;
            resourceExecution = resourceLockManager.createExecution();
            resourceExecutions.add(resourceExecution);
            monitoredExecution = taskExecutionRegistry.begin(
                    this,
                    taskExecutionTitle(),
                    taskExecutionUserVisible());
            monitoredExecutions.put(resourceExecution, monitoredExecution);
            // A direct cancel can re-enter from the registry's initial publication before the monitoring map exists.
            // Handoff the cancellation marker after installing the handle so that invocation history cannot miss it.
            cancellationToRun = monitoredExecution.installCancellation(() -> cancel(resourceExecution));
            if (cancelledExecutions.contains(resourceExecution)) {
                taskExecutionRegistry.markCancellationRequested(monitoredExecution.id());
            }
        }
        AtomicBoolean stopNotificationAttempted = new AtomicBoolean();
        AtomicReference<@Nullable Throwable> invocationFailure = new AtomicReference<>();
        try {
            taskExecutionProgressHandle.bind(monitoredExecution.id());
        } catch (Error bindFailure) {
            failure = bindFailure;
            invocationFailure.set(bindFailure);
            try {
                stopInvocation(
                        resourceExecution,
                        monitoredExecution,
                        false,
                        invocationFailure,
                        stopNotificationAttempted);
            } catch (RuntimeException | Error stopFailure) {
                if (stopFailure != bindFailure) {
                    bindFailure.addSuppressed(stopFailure);
                }
            }
            removeExecutionWhenClean(resourceExecution);
            throw bindFailure;
        }
        if (cancellationToRun != null) {
            try {
                cancellationToRun.run();
            } catch (RuntimeException | Error cancellationFailure) {
                failure = cancellationFailure;
                invocationFailure.set(cancellationFailure);
                try {
                    stopInvocation(
                            resourceExecution,
                            monitoredExecution,
                            false,
                            invocationFailure,
                            stopNotificationAttempted);
                } catch (RuntimeException | Error stopFailure) {
                    if (stopFailure != cancellationFailure) {
                        cancellationFailure.addSuppressed(stopFailure);
                    }
                }
                removeExecutionWhenClean(resourceExecution);
                throw cancellationFailure;
            }
        }
        try {
            monitoredExecution.started();
            notifyTaskListeners(TaskListener::onStart);
        } catch (RuntimeException | Error startFailure) {
            failure = startFailure;
            invocationFailure.set(startFailure);
            try {
                stopInvocation(
                        resourceExecution,
                        monitoredExecution,
                        false,
                        invocationFailure,
                        stopNotificationAttempted);
            } catch (RuntimeException | Error stopFailure) {
                if (stopFailure != startFailure) {
                    startFailure.addSuppressed(stopFailure);
                }
            }
            removeExecutionWhenClean(resourceExecution);
            throw startFailure;
        }
        try {
            future = executeTasks(null, null, resourceExecution, Collections.singleton(firstTask))
                    .handleAsync((@Nullable Exception exception, @Nullable Throwable throwable) -> {
                        boolean requestedSuccess = exception == null && throwable == null;
                        AtomicBoolean effectiveSuccess = new AtomicBoolean();
                        try {
                            if (throwable != null) {
                                Throwable resolvedFailure = resolveException(throwable);
                                failure = resolvedFailure;
                                invocationFailure.set(resolvedFailure);
                                Lang.handleUncaughtException(resolvedFailure);
                            } else {
                                failure = exception;
                                if (exception != null) {
                                    invocationFailure.set(exception);
                                    // We log exception stacktrace because some exceptions indicate launcher defects.
                                    LOG.warning("An exception occurred in task execution", exception);

                                    Throwable resolvedException = resolveException(exception);
                                    if (resolvedException instanceof RuntimeException
                                            && !(resolvedException instanceof CancellationException)
                                            && !(resolvedException instanceof JsonParseException)
                                            && !(resolvedException instanceof RejectedExecutionException)) {
                                        // Track unexpected RuntimeException without classifying known user failures.
                                        @Nullable Thread.UncaughtExceptionHandler handler = uncaughtExceptionHandler;
                                        if (handler != null) {
                                            handler.uncaughtException(
                                                    Thread.currentThread(), resolvedException);
                                        }
                                    }
                                }
                            }
                        } finally {
                            effectiveSuccess.set(stopInvocation(
                                    resourceExecution,
                                    monitoredExecution,
                                    requestedSuccess,
                                    invocationFailure,
                                    stopNotificationAttempted));
                        }

                        return effectiveSuccess.get();
                    })
                    .exceptionally(e -> {
                        Throwable resolved = resolveException(e);
                        @Nullable Throwable previousFailure = failure;
                        if (previousFailure != null && previousFailure != resolved) {
                            resolved.addSuppressed(previousFailure);
                        }
                        failure = resolved;
                        invocationFailure.set(resolved);
                        try {
                            stopInvocation(
                                    resourceExecution,
                                    monitoredExecution,
                                    false,
                                    invocationFailure,
                                    stopNotificationAttempted);
                        } catch (RuntimeException | Error stopFailure) {
                            if (stopFailure != resolved) {
                                resolved.addSuppressed(stopFailure);
                            }
                        }
                        Lang.handleUncaughtException(resolved);
                        return false;
                    })
                    .whenComplete((@Nullable Boolean success, @Nullable Throwable throwable) ->
                            removeExecutionWhenClean(resourceExecution));
        } catch (RuntimeException | Error startFailure) {
            failure = startFailure;
            invocationFailure.set(startFailure);
            try {
                stopInvocation(
                        resourceExecution,
                        monitoredExecution,
                        false,
                        invocationFailure,
                        stopNotificationAttempted);
            } catch (RuntimeException | Error stopFailure) {
                if (stopFailure != startFailure) {
                    startFailure.addSuppressed(stopFailure);
                }
            }
            removeExecutionWhenClean(resourceExecution);
            throw startFailure;
        }
        return this;
    }

    /// Returns residual resource descriptions retained by this executor's execution domains.
    @Override
    public @Unmodifiable List<String> getResidualResources() {
        return resourceLockManager.residualResourceDescriptions(resourceExecutions);
    }

    /// Retries residual lease release before a coordinator starts a new task attempt.
    @Override
    public boolean retryResourceCleanup() {
        @Unmodifiable List<String> residual = resourceLockManager.retryResidualCleanup(resourceExecutions);
        removeCleanExecutions();
        return residual.isEmpty();
    }

    /// Removes clean execution domains from both residual tracking and cancellation classification.
    private void removeCleanExecutions() {
        resourceExecutions.removeIf(execution -> {
            if (!isExecutionClean(execution)) {
                return false;
            }
            terminalResourceExecutions.remove(execution);
            cancelledExecutions.remove(execution);
            monitoredExecutions.remove(execution);
            return true;
        });
    }

    /// Removes an execution domain once its resource manager no longer retains residual leases.
    private void removeExecutionWhenClean(TaskResourceLockManager.Execution execution) {
        if (isExecutionClean(execution)) {
            resourceExecutions.remove(execution);
            terminalResourceExecutions.remove(execution);
            cancelledExecutions.remove(execution);
            monitoredExecutions.remove(execution);
        }
    }

    /// Returns whether one execution domain has no residual lease.
    private boolean isExecutionClean(TaskResourceLockManager.Execution execution) {
        return resourceLockManager.residualResourceDescriptions(Set.of(execution)).isEmpty();
    }

    /// Returns the monitoring handle associated with one execution domain, or null after late cleanup.
    private @Nullable TaskExecutionRegistry.Execution monitoredExecution(
            TaskResourceLockManager.Execution resourceExecution) {
        return monitoredExecutions.get(resourceExecution);
    }

    /// Publishes one monitored task-ready event while tolerating a late callback after domain cleanup.
    private void monitorTaskReady(
            @Nullable Task<?> parentTask,
            TaskResourceLockManager.Execution resourceExecution,
            Task<?> task) {
        @Nullable TaskExecutionRegistry.Execution monitor = monitoredExecution(resourceExecution);
        if (monitor != null) {
            monitor.taskReady(parentTask, task);
        }
    }

    /// Publishes one monitored task-running event while tolerating a late callback after domain cleanup.
    private void monitorTaskRunning(
            @Nullable Task<?> parentTask,
            TaskResourceLockManager.Execution resourceExecution,
            Task<?> task) {
        @Nullable TaskExecutionRegistry.Execution monitor = monitoredExecution(resourceExecution);
        if (monitor != null) {
            monitor.taskRunning(parentTask, task);
        }
    }

    /// Publishes one monitored task-finished event while tolerating a late callback after domain cleanup.
    private void monitorTaskFinished(
            @Nullable Task<?> parentTask,
            TaskResourceLockManager.Execution resourceExecution,
            Task<?> task) {
        @Nullable TaskExecutionRegistry.Execution monitor = monitoredExecution(resourceExecution);
        if (monitor != null) {
            monitor.taskFinished(parentTask, task);
        }
    }

    /// Publishes one monitored task-failed event while tolerating a late callback after domain cleanup.
    private void monitorTaskFailed(
            @Nullable Task<?> parentTask,
            TaskResourceLockManager.Execution resourceExecution,
            Task<?> task,
            Throwable failure) {
        @Nullable TaskExecutionRegistry.Execution monitor = monitoredExecution(resourceExecution);
        if (monitor != null) {
            monitor.taskFailed(parentTask, task, failure);
        }
    }

    /// Publishes one monitored task-property event while tolerating a late callback after domain cleanup.
    private void monitorTaskPropertiesUpdated(
            @Nullable Task<?> parentTask,
            TaskResourceLockManager.Execution resourceExecution,
            Task<?> task) {
        @Nullable TaskExecutionRegistry.Execution monitor = monitoredExecution(resourceExecution);
        if (monitor != null) {
            monitor.taskPropertiesUpdated(parentTask, task);
        }
    }

    /// Attempts the single terminal listener notification promised for one execution chain.
    ///
    /// The guard is set before invoking listeners so an [Error] raised by one terminal listener cannot recursively
    /// trigger a second `onStop` notification from the future recovery stage.
    ///
    /// @param attempted per-execution terminal notification guard
    /// @param success whether the execution chain completed successfully
    /// @param invocationCancelled whether the invocation was cancelled
    private void notifyStopOnce(AtomicBoolean attempted, boolean success, boolean invocationCancelled) {
        if (attempted.compareAndSet(false, true)) {
            notifyTaskListenersForInvocation(
                    invocationCancelled,
                    it -> it.onStop(success, this));
        }
    }

    /// Commits one invocation's terminal result while serializing it with direct cancellation.
    ///
    /// The registry also participates in the decision because a public row-cancellation request changes its state
    /// before invoking the executor callback. Reading both cancellation domains while holding this executor's monitor
    /// keeps the legacy `onStop` result aligned with the top-level registry terminal state.
    ///
    /// @param resourceExecution invocation resource domain
    /// @param monitoredExecution top-level registry handle
    /// @param requestedSuccess result reported by the task future before a late cancellation check
    /// @param invocationFailure mutable terminal failure holder
    /// @param stopNotificationAttempted one-shot legacy stop-notification guard
    /// @return effective success after cancellation arbitration
    private boolean stopInvocation(
            TaskResourceLockManager.Execution resourceExecution,
            TaskExecutionRegistry.Execution monitoredExecution,
            boolean requestedSuccess,
            AtomicReference<@Nullable Throwable> invocationFailure,
            AtomicBoolean stopNotificationAttempted) {
        synchronized (this) {
            boolean success = requestedSuccess
                    && !isExecutionCancelled(resourceExecution)
                    && !monitoredExecution.cancellationRequested();
            if (!success && requestedSuccess && invocationFailure.get() == null) {
                CancellationException cancellation = new CancellationException("Cancelled by user");
                // Row-level cancellation has an invocation-local domain, so the legacy executor failure surface must
                // also receive a synthetic cancellation cause for existing launch-session classification.
                failure = cancellation;
                invocationFailure.set(cancellation);
            }
            TaskExecutionStatus terminalStatus = monitoredExecution.stopped(success, invocationFailure.get());
            boolean effectiveSuccess = terminalStatus == TaskExecutionStatus.SUCCEEDED;
            if (!effectiveSuccess && requestedSuccess && invocationFailure.get() == null) {
                CancellationException cancellation = new CancellationException("Cancelled by user");
                failure = cancellation;
                invocationFailure.set(cancellation);
            }
            notifyStopOnce(
                    stopNotificationAttempted,
                    effectiveSuccess,
                    terminalStatus == TaskExecutionStatus.CANCELLED);
            return effectiveSuccess;
        }
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
        @Nullable Throwable firstFailure = null;
        Set<TaskExecutionRegistry.Execution> markedExecutions = new HashSet<>();
        for (TaskResourceLockManager.Execution resourceExecution : resourceExecutions) {
            if (terminalResourceExecutions.contains(resourceExecution)) {
                // A terminal cleanup domain is already committed and must retain pending acquisition after cancel.
                continue;
            }
            @Nullable TaskExecutionRegistry.Execution monitored = monitoredExecutions.get(resourceExecution);
            if (monitored != null && markedExecutions.add(monitored)) {
                taskExecutionRegistry.markCancellationRequested(monitored.id());
            }
            try {
                cancel(resourceExecution);
            } catch (RuntimeException | Error cancellationFailure) {
                // A cleanup race in one execution domain must not leave later domains running. Preserve the first
                // failure for the caller after every domain has received the cancellation request.
                if (firstFailure == null) {
                    firstFailure = cancellationFailure;
                } else if (firstFailure != cancellationFailure) {
                    firstFailure.addSuppressed(cancellationFailure);
                }
            }
        }
        if (firstFailure instanceof RuntimeException cancellationFailure) {
            throw cancellationFailure;
        }
        if (firstFailure instanceof Error cancellationFailure) {
            throw cancellationFailure;
        }
    }

    /// Cancels one invocation domain without changing cancellation state of sibling starts.
    ///
    /// The public [#cancel()] method retains its historical all-invocations behavior. The task registry calls this
    /// overload with the resource domain captured by one [#start()] invocation so a row-level cancellation cannot
    /// stop another overlapping row.
    private synchronized void cancel(TaskResourceLockManager.Execution resourceExecution) {
        if (terminalResourceExecutions.contains(resourceExecution)
                || !resourceExecutions.contains(resourceExecution)) {
            return;
        }
        if (!cancelledExecutions.add(resourceExecution)) {
            return;
        }
        try {
            resourceLockManager.cancel(resourceExecution);
        } catch (RuntimeException | Error cancellationFailure) {
            cancelledExecutions.remove(resourceExecution);
            throw cancellationFailure;
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
                    if (isExecutionCancelled(resourceExecution) && !hasTerminalCleanup) {
                        CancellationException cancellation = new CancellationException("Cancelled by user");
                        for (Task<?> task : tasks) {
                            task.resetExecutionOutcome();
                            monitorTaskReady(parentTask, resourceExecution, task);
                            task.setException(cancellation);
                            monitorTaskFailed(parentTask, resourceExecution, task, cancellation);
                        }
                        return CompletableFuture.runAsync(() -> checkCancellation(resourceExecution));
                    }

                    return CompletableFuture.allOf(tasks.stream()
                            .map(task -> {
                                boolean terminalCleanup = task.isTerminalCleanup();
                                TaskResourceLockManager.Execution taskExecution = terminalCleanup
                                        ? resourceLockManager.createExecution()
                                        : resourceExecution;
                                if (terminalCleanup) {
                                    // Terminal cleanup deliberately has an independent cancellation domain, but its
                                    // lease must remain visible to the owning executor when release leaves a residual
                                    // process-local write block.
                                    terminalResourceExecutions.add(taskExecution);
                                    resourceExecutions.add(taskExecution);
                                    // Cleanup tasks still belong to the same top-level workflow. Reuse its monitoring
                                    // handle even though their resource lease has a separate cancellation domain.
                                    @Nullable TaskExecutionRegistry.Execution monitor =
                                            monitoredExecution(resourceExecution);
                                    if (monitor != null) {
                                        monitoredExecutions.put(taskExecution, monitor);
                                    }
                                }
                                CompletableFuture<?> taskFuture =
                                        CompletableFuture.<@Nullable Void>completedFuture(null)
                                                .thenComposeAsync((@Nullable Void unused2) -> executeTask(
                                                        parentTask,
                                                        parentOwner,
                                                        taskExecution,
                                                        task));
                                return terminalCleanup
                                        ? taskFuture.whenComplete((@Nullable Object ignored, @Nullable Throwable failure) ->
                                                removeExecutionWhenClean(taskExecution))
                                        : taskFuture;
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

    /// Executes prerequisite siblings and exposes an Error only to an explicitly opted-in recovery coordinator.
    ///
    /// Ordinary task graphs retain [#executeTasks] and propagate Error immediately. The recovery path converts the
    /// complete terminal throwable to a value so the coordinator can construct its cleanup dependency first.
    ///
    /// @param task recovery coordinator requesting prerequisite execution
    /// @param owner resource owner of the recovery coordinator
    /// @param resourceExecution cancellation domain for the task chain
    /// @return future carrying the complete optional prerequisite failure
    private CompletableFuture<@Nullable Throwable> executeDependentsFor(
            Task<?> task,
            TaskResourceLockManager.Owner owner,
            TaskResourceLockManager.Execution resourceExecution) {
        if (!task.acceptsDependentErrors()) {
            return executeTasks(task, owner, resourceExecution, task.getDependents())
                    .thenApply(failure -> failure);
        }
        return executeTasksExceptionally(task, owner, resourceExecution, task.getDependents())
                .handle((@Nullable Void ignored, @Nullable Throwable failure) ->
                        failure == null ? null : resolveException(failure));
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
                    task.getResourceDeclarations(),
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

        return withLeaseRelease(
                handleCompletableFutureTaskCompletion(parentTask, resourceExecution, task, execution),
                leaseReference);
    }

    /// Runs the established future-task lifecycle after its semantic resources have been acquired.
    private <T> CompletableFuture<@Nullable T> executeCompletableFutureTaskLifecycle(
            @Nullable Task<?> parentTask,
            TaskResourceLockManager.Owner owner,
            TaskResourceLockManager.Execution resourceExecution,
            CompletableFutureTask<T> task) {
        return CompletableFuture.<@Nullable Void>completedFuture(null)
                .thenComposeAsync((@Nullable Void unused) -> {
                    checkCancellation(resourceExecution, task);

                    task.setCancelled(() -> isExecutionCancelled(resourceExecution));
                    task.setState(Task.TaskState.READY);
                    if (parentTask != null && task.getStage() == null)
                        task.setStage(parentTask.getStage());
                    task.setNotifyPropertiesChanged(() -> {
                        notifyTaskListeners(task, it -> it.onPropertiesUpdate(task));
                        monitorTaskPropertiesUpdated(parentTask, resourceExecution, task);
                    });

                    if (task.getSignificance().shouldLog())
                        LOG.trace("Executing task: " + task.getName());

                    notifyTaskListeners(it -> it.onReady(task));
                    monitorTaskReady(parentTask, resourceExecution, task);
                    task.setState(Task.TaskState.RUNNING);
                    notifyTaskListeners(it -> it.onRunning(task));
                    monitorTaskRunning(parentTask, resourceExecution, task);

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
                    checkCancellation(resourceExecution, task);

                    if (task.getSignificance().shouldLog()) {
                        LOG.trace("Task finished: " + task.getName());
                    }

                    task.setResult(result);
                    task.fireDoneEvent(this, false);
                    notifyTaskListeners(it -> it.onFinished(task));
                    monitorTaskFinished(parentTask, resourceExecution, task);

                    task.setState(Task.TaskState.SUCCEEDED);

                    return result;
                });
    }

    /// Applies the established future-task failure classification before the resource lease is released.
    private <T> CompletableFuture<@Nullable T> handleCompletableFutureTaskCompletion(
            @Nullable Task<?> parentTask,
            TaskResourceLockManager.Execution resourceExecution,
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
                            monitorTaskFailed(parentTask, resourceExecution, task, e);
                        } else {
                            task.setException(e);
                            exception = e;
                            if (task.getSignificance().shouldLog()) {
                                LOG.trace("Task failed: " + task.getName(), e);
                            }
                            task.fireDoneEvent(this, true);
                            notifyTaskListeners(it -> it.onFailed(task, e));
                            monitorTaskFailed(parentTask, resourceExecution, task, e);
                        }

                        task.setState(Task.TaskState.FAILED);
                    } else {
                        handleNonExceptionFailure(parentTask, resourceExecution, task, resolved);
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
                    task.getResourceDeclarations(),
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

        return withLeaseRelease(
                handleNormalTaskCompletion(
                        parentTask,
                        resourceExecution,
                        task,
                        ownerForCompletion,
                        execution,
                        leaseReference),
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
                    checkCancellation(resourceExecution, task);

                    task.setCancelled(() -> isExecutionCancelled(resourceExecution));
                    task.setState(Task.TaskState.READY);
                    if (task.getStage() != null) {
                        task.setInheritedStage(task.getStage());
                    } else if (parentTask != null) {
                        task.setInheritedStage(parentTask.getInheritedStage());
                    }
                    task.setNotifyPropertiesChanged(() -> {
                        notifyTaskListeners(task, it -> it.onPropertiesUpdate(task));
                        monitorTaskPropertiesUpdated(parentTask, resourceExecution, task);
                    });

                    if (task.getSignificance().shouldLog())
                        LOG.trace("Executing task: " + task.getName());

                    notifyTaskListeners(task, it -> it.onReady(task));
                    monitorTaskReady(parentTask, resourceExecution, task);

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
                .thenComposeAsync((@Nullable Void unused) -> executeDependentsFor(task, owner, resourceExecution))
                .thenComposeAsync((@Nullable Throwable dependentsFailure) -> {
                    boolean isDependentsSucceeded = dependentsFailure == null;

                    if (isDependentsSucceeded) {
                        task.setDependentsSucceeded();
                        task.setDependentFailure(null);
                    } else {
                        task.setDependentFailure(dependentsFailure);
                        if (dependentsFailure instanceof Exception exception) {
                            task.setException(exception);
                        }

                        if (task.isRelyingOnDependents()) {
                            rethrow(dependentsFailure);
                        }
                    }

                    NestedTaskScope scope = new NestedTaskScope(task, owner, resourceExecution);
                    CompletableFuture<@Nullable Void> mainExecution = CompletableFuture.runAsync(wrap(() -> {
                        task.setState(Task.TaskState.RUNNING);
                        notifyTaskListeners(task, it -> it.onRunning(task));
                        monitorTaskRunning(parentTask, resourceExecution, task);
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

                    checkCancellation(resourceExecution, task);

                    if (task.getSignificance().shouldLog()) {
                        LOG.trace("Task finished: " + task.getName());
                    }

                    task.fireDoneEvent(this, false);
                    notifyTaskListeners(task, it -> it.onFinished(task));
                    monitorTaskFinished(parentTask, resourceExecution, task);

                    task.setState(Task.TaskState.SUCCEEDED);
                    return task.getResult();
                });
    }

    /// Applies the established regular-task failure classification before the resource lease is released.
    private <T> CompletableFuture<@Nullable T> handleNormalTaskCompletion(
            @Nullable Task<?> parentTask,
            TaskResourceLockManager.Execution resourceExecution,
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
                        return classifyNormalTaskFailure(parentTask, resourceExecution, task, throwable);
                    })
                    .thenCompose(stage -> stage);
        }).thenCompose(stage -> stage);
    }

    /// Classifies one established regular-task failure and republishes its historical terminal callbacks.
    private <T> CompletableFuture<@Nullable T> classifyNormalTaskFailure(
            @Nullable Task<?> parentTask,
            TaskResourceLockManager.Execution resourceExecution,
            Task<T> task,
            Throwable throwable) {
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
            monitorTaskFailed(parentTask, resourceExecution, task, e);

            task.setState(Task.TaskState.FAILED);
        } else {
            handleNonExceptionFailure(parentTask, resourceExecution, task, resolved);
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

    /// Completes a non-Exception task failure while preserving the original throwable for the global handler.
    ///
    /// @param parentTask parent task in the monitored execution tree
    /// @param resourceExecution invocation resource domain
    /// @param task task whose terminal callbacks must be published
    /// @param failure original non-Exception failure
    private void handleNonExceptionFailure(
            @Nullable Task<?> parentTask,
            TaskResourceLockManager.Execution resourceExecution,
            Task<?> task,
            Throwable failure) {
        Exception taskException = new Exception(failure);
        task.setException(taskException);
        exception = taskException;
        task.fireDoneEvent(this, true);
        notifyTaskListeners(task, it -> it.onFailed(task, failure));
        monitorTaskFailed(parentTask, resourceExecution, task, failure);
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
        task.resetExecutionOutcome();
        monitorTaskReady(parentTask, resourceExecution, task);
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

        /// Whether the early handoff release retained a residual lease after a cleanup failure.
        ///
        /// A residual marker deliberately blocks a new acquisition.  Reacquiring the same owner before terminal
        /// callbacks would therefore wait on the marker it is responsible for clearing, leaving the task permanently
        /// RUNNING.  The terminal release path retries the retained lease instead.
        private boolean handoffCleanupFailed;

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
            }
            try {
                closeLeases(leasesToClose);
                synchronized (this) {
                    if (!released) {
                        leases.removeAll(leasesToClose);
                    }
                }
            } catch (RuntimeException | Error failure) {
                synchronized (this) {
                    handoffCleanupFailed = true;
                }
                throw failure;
            }
        }

        /// Returns whether this invocation released a lease that must be reacquired for terminal callbacks.
        private synchronized boolean needsTerminalReacquisition() {
            return handedOff && !handoffCleanupFailed && !released;
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
            closeLeases(leasesToClose);
        }

        /// Attempts every lease release and preserves all failures for the task's terminal path.
        ///
        /// A lease can retain a process-local residual marker when its manager cleanup fails.  Stopping at the first
        /// exception would lose the remaining leases after this reference has cleared its ownership list, making those
        /// leases impossible to release or report through the executor retry boundary.  Every lease is therefore
        /// attempted and subsequent failures are attached to the first one.
        ///
        /// @param leases leases captured outside the reference monitor
        private static void closeLeases(@Unmodifiable List<TaskResourceLockManager.Lease> leases) {
            @Nullable Throwable firstFailure = null;
            for (TaskResourceLockManager.Lease lease : leases) {
                try {
                    lease.close();
                } catch (RuntimeException | Error failure) {
                    if (firstFailure == null) {
                        firstFailure = failure;
                    } else if (firstFailure != failure) {
                        firstFailure.addSuppressed(failure);
                    }
                }
            }
            if (firstFailure instanceof RuntimeException failure) {
                throw failure;
            }
            if (firstFailure instanceof Error failure) {
                throw failure;
            }
        }
    }

    /// Returns whether one invocation resource domain has been cancelled.
    private boolean isExecutionCancelled(TaskResourceLockManager.Execution resourceExecution) {
        return cancelledExecutions.contains(resourceExecution);
    }

    /// Throws a cancellation exception for one invocation resource domain.
    private void checkCancellation(TaskResourceLockManager.Execution resourceExecution) {
        if (isExecutionCancelled(resourceExecution)) {
            throw new CancellationException("Cancelled by user");
        }
    }

    /// Throws a cancellation exception unless an already-committed terminal cleanup must still run.
    ///
    /// @param task task about to cross a cancellation checkpoint
    private void checkCancellation(TaskResourceLockManager.Execution resourceExecution, Task<?> task) {
        if (!task.isTerminalCleanup()) {
            checkCancellation(resourceExecution);
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
