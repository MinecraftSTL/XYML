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
package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.analyzer.RepairCheckpoint;
import space.minecraftstl.xyml.game.analyzer.RepairTaskFactory;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Function;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Owns asynchronous repair-task executions exposed through MCP.
///
/// Each operation receives a fresh [Task], installs its terminal listener before startup, and retains only a bounded
/// status snapshot after completion. Cancellation remains cooperative because that is the underlying task contract.
@NotNullByDefault
public final class McpTaskOperationRegistry implements AutoCloseable {
    /// Maximum length of one failure message returned to an MCP client.
    private static final int MAX_FAILURE_MESSAGE_LENGTH = 512;

    /// Serializes operation registration, startup, cancellation, completion, and cleanup.
    private final Object stateLock = new Object();

    /// Time source used for timestamps and deterministic expiry tests.
    private final Clock clock;

    /// Retention applied to terminal operation snapshots.
    private final Duration terminalRetention;

    /// Maximum number of retained active and terminal operations.
    private final int maximumOperations;

    /// Factory used to create one executor for a task; the package-level overload is used by lifecycle tests.
    private final Function<Task<?>, TaskExecutor> executorFactory;

    /// Insertion-ordered operation state indexed by opaque identifier.
    private final Map<String, Operation> operations = new LinkedHashMap<>();

    /// Optional owner callback notified after an operation reaches a terminal state.
    ///
    /// The callback is deliberately outside the public MCP surface. It lets an application-level coordinator update
    /// its plan before this bounded registry is allowed to expire the operation snapshot.
    private @Nullable Consumer<String> completionListener;

    /// Whether this registry no longer accepts new operations.
    private boolean closed;

    /// Creates a production registry with bounded terminal-state retention.
    public McpTaskOperationRegistry() {
        this(Clock.systemUTC(), Duration.ofMinutes(30), 256, Task::executor);
    }

    /// Creates a registry with explicit time and capacity policies.
    ///
    /// @param clock timestamp source
    /// @param terminalRetention duration for which terminal snapshots remain queryable
    /// @param maximumOperations maximum retained operation count
    public McpTaskOperationRegistry(Clock clock, Duration terminalRetention, int maximumOperations) {
        this(clock, terminalRetention, maximumOperations, Task::executor);
    }

    /// Creates a registry with an explicit task-executor factory for package-level lifecycle tests.
    ///
    /// Production callers should use [#McpTaskOperationRegistry()] or the retention-policy constructor so tasks use
    /// their normal asynchronous executor. The factory is retained only to make residual-cleanup transitions
    /// deterministic without exposing task internals through the public API.
    ///
    /// @param clock timestamp source
    /// @param terminalRetention duration for which terminal snapshots remain queryable
    /// @param maximumOperations maximum retained operation count
    /// @param executorFactory factory creating an executor for each fresh task
    McpTaskOperationRegistry(
            Clock clock,
            Duration terminalRetention,
            int maximumOperations,
            Function<Task<?>, TaskExecutor> executorFactory) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.terminalRetention = requirePositive(terminalRetention, "terminalRetention");
        if (maximumOperations <= 0) {
            throw new IllegalArgumentException("maximumOperations must be positive");
        }
        this.maximumOperations = maximumOperations;
        this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory");
    }

    /// Installs the in-process owner callback used to observe terminal operation transitions.
    ///
    /// @param listener callback receiving the opaque operation identifier, or null to remove it
    void setCompletionListener(@Nullable Consumer<String> listener) {
        synchronized (stateLock) {
            completionListener = listener;
        }
    }

    /// Retains a terminal operation for its application-level owner until that owner releases the link.
    ///
    /// The operation can finish synchronously inside [#start], before the caller has received its identifier. This
    /// explicit pin closes that hand-off race and prevents a coordinator from observing a permanently running plan
    /// after the bounded operation snapshot expires.
    ///
    /// @param operationId opaque operation identifier
    /// @throws IllegalArgumentException when the operation is unknown or already expired
    void retainForOwner(String operationId) {
        synchronized (stateLock) {
            pruneLocked();
            Operation operation = requireOperationLocked(operationId);
            operation.ownerRetained = true;
        }
    }

    /// Releases an application-level retention pin after its plan link is no longer needed.
    ///
    /// @param operationId opaque operation identifier
    void releaseOwnerRetention(String operationId) {
        synchronized (stateLock) {
            @Nullable Operation operation = operations.get(operationId);
            if (operation != null) {
                operation.ownerRetained = false;
                pruneLocked();
            }
        }
    }

    /// Creates, observes, and starts one fresh repair task.
    ///
    /// Task-creation and startup failures are represented by a queryable failed operation. [Error] values are recorded
    /// and then rethrown so process-level failure policy remains authoritative.
    ///
    /// @param actionType stable repair action type
    /// @param cancellable whether an MCP client may request cooperative cancellation
    /// @param taskFactory factory creating exactly one fresh stopped task for this operation
    /// @return immutable initial or terminal operation snapshot
    public @Unmodifiable Map<String, Object> start(
            String actionType,
            boolean cancellable,
            RepairTaskFactory taskFactory) {
        return startInternal(actionType, cancellable, RepairCheckpoint.initial(), taskFactory, false);
    }

    /// Creates one fresh repair task while reserving its operation for an application owner.
    ///
    /// The reservation is installed before task startup, closing the capacity hand-off race in which a synchronous
    /// terminal task could otherwise be evicted before its coordinator publishes the operation-to-plan link. The
    /// caller must release the reservation with [#releaseOwnerRetention(String)] once the link is gone.
    ///
    /// @param actionType stable repair action type
    /// @param cancellable whether an MCP client may request cooperative cancellation
    /// @param taskFactory factory creating exactly one fresh stopped task
    /// @return immutable initial or terminal operation snapshot
    @Unmodifiable Map<String, Object> startForOwner(
            String actionType,
            boolean cancellable,
            RepairTaskFactory taskFactory) {
        return startForOwner(actionType, cancellable, RepairCheckpoint.initial(), taskFactory);
    }

    /// Creates one fresh repair task for an application owner using a prior progress checkpoint.
    ///
    /// The checkpoint is copied into the operation snapshot before task creation, so a task-factory failure still
    /// leaves the caller with enough information to retry from the same failed step.
    ///
    /// @param actionType stable repair action type
    /// @param cancellable whether an MCP client may request cooperative cancellation
    /// @param checkpoint progress retained from the previous attempt
    /// @param taskFactory factory creating exactly one fresh stopped task
    /// @return immutable initial or terminal operation snapshot
    @Unmodifiable Map<String, Object> startForOwner(
            String actionType,
            boolean cancellable,
            RepairCheckpoint checkpoint,
            RepairTaskFactory taskFactory) {
        return startInternal(
                actionType,
                cancellable,
                Objects.requireNonNull(checkpoint, "checkpoint"),
                taskFactory,
                true);
    }

    /// Creates, observes, and starts one task with an optional owner retention reservation.
    ///
    /// @param actionType stable repair action type
    /// @param cancellable whether cancellation is supported
    /// @param taskFactory fresh stopped task factory
    /// @param retainForOwner whether the operation is pinned before startup
    /// @return immutable operation snapshot
    private @Unmodifiable Map<String, Object> startInternal(
            String actionType,
            boolean cancellable,
            RepairCheckpoint checkpoint,
            RepairTaskFactory taskFactory,
            boolean retainForOwner) {
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(taskFactory, "taskFactory");

        Operation operation;
        synchronized (stateLock) {
            requireOpenLocked();
            pruneLocked();
            ensureCapacityLocked();
            operation = new Operation(
                    UUID.randomUUID().toString(),
                    actionType,
                    cancellable,
                    clock.instant(),
                    checkpoint);
            operation.ownerRetained = retainForOwner;
            operations.put(operation.id, operation);
        }

        final Task<?> task;
        final TaskExecutor executor;
        final Subscription subscription;
        try {
            task = Objects.requireNonNull(
                    taskFactory.createTask(checkpoint),
                    "repair task factory returned null");
            executor = Objects.requireNonNull(
                    executorFactory.apply(task),
                    "task executor factory returned null");
            subscription = executor.subscribeTaskListener(new CompletionListener(operation));
        } catch (RuntimeException creationFailure) {
            failBeforeStart(operation, creationFailure);
            notifyCompletion(operation);
            return snapshot(operation);
        } catch (Error error) {
            failBeforeStart(operation, error);
            releaseUnpublishedOwnerRetention(operation, retainForOwner);
            throw error;
        }

        boolean shouldStart;
        try {
            synchronized (stateLock) {
                shouldStart = !closed && !operation.status.terminal && !operation.cancellationRequested;
                if (shouldStart) {
                    operation.executor = executor;
                    operation.subscription = subscription;
                    // start() publishes onStart synchronously. Holding the reentrant lock makes close linearizable.
                    executor.start();
                } else {
                    operation.status = Status.CANCELLED;
                    operation.finishedAt = clock.instant();
                    operation.cancellationRequested = true;
                }
            }
        } catch (RuntimeException startFailure) {
            finishStartFailure(operation, startFailure);
            notifyCompletion(operation);
            return snapshot(operation);
        } catch (Error error) {
            finishStartFailure(operation, error);
            releaseUnpublishedOwnerRetention(operation, retainForOwner);
            throw error;
        }
        if (!shouldStart) {
            try {
                subscription.unsubscribe();
            } catch (RuntimeException | Error unsubscribeFailure) {
                // Cancellation has already been committed under the state lock; a faulty listener handle must not
                // turn a valid terminal operation into an owner-retention leak.
                LOG.warning("Unable to detach cancelled crash repair operation listener " + operation.id,
                        unsubscribeFailure);
            }
            notifyCompletion(operation);
        }
        return snapshot(operation);
    }

    /// Notifies the application owner after a terminal state has been committed.
    ///
    /// Owner publication is advisory and must not make task creation, startup, cancellation, or cleanup appear to
    /// fail after the registry already committed a queryable terminal snapshot.
    ///
    /// @param operation terminal operation being published
    private void notifyCompletion(Operation operation) {
        @Nullable Consumer<String> listener;
        synchronized (stateLock) {
            if (operation.completionNotified) {
                return;
            }
            operation.completionNotified = true;
            listener = completionListener;
        }
        if (listener == null) {
            return;
        }
        try {
            listener.accept(operation.id);
        } catch (RuntimeException | Error callbackFailure) {
            LOG.warning("Unable to publish crash repair operation completion " + operation.id, callbackFailure);
        }
    }

    /// Drops an owner reservation when startup aborts before an operation identifier can be returned.
    ///
    /// @param operation operation whose caller never received the identifier
    /// @param retained whether startup installed an owner reservation
    private void releaseUnpublishedOwnerRetention(Operation operation, boolean retained) {
        if (!retained) {
            return;
        }
        synchronized (stateLock) {
            operation.ownerRetained = false;
            pruneLocked();
        }
    }

    /// Returns the latest immutable state for one operation.
    ///
    /// @param operationId opaque operation identifier
    /// @return immutable operation snapshot
    /// @throws IllegalArgumentException when the operation is unknown or expired
    public @Unmodifiable Map<String, Object> status(String operationId) {
        return snapshot(requireOperation(operationId));
    }

    /// Requests cooperative cancellation for one active operation.
    ///
    /// @param operationId opaque operation identifier
    /// @return immutable state including whether this request was accepted
    /// @throws IllegalArgumentException when the operation is unknown or expired
    public @Unmodifiable Map<String, Object> cancel(String operationId) {
        Operation operation;
        @Nullable TaskExecutor executorToCancel = null;
        boolean accepted;
        synchronized (stateLock) {
            pruneLocked();
            operation = requireOperationLocked(operationId);
            accepted = operation.cancellable && !operation.status.terminal;
            if (accepted) {
                operation.cancellationRequested = true;
                executorToCancel = operation.executor;
            }
        }
        cancelExecutor(operation, executorToCancel);
        Map<String, Object> result = new LinkedHashMap<>(snapshot(operation));
        result.put("cancellation_accepted", accepted);
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    /// Retries one operation's retained resource cleanup without creating a new task.
    ///
    /// This bounded pass is used immediately before a crash-repair plan retry. It never performs repair work and does
    /// not alter the terminal operation identity; callers can inspect `cleanup_succeeded` and retry the plan later.
    ///
    /// @param operationId opaque operation identifier
    /// @return immutable cleanup status
    public @Unmodifiable Map<String, Object> retryResourceCleanup(String operationId) {
        Operation operation;
        synchronized (stateLock) {
            pruneLocked();
            operation = requireOperationLocked(operationId);
        }
        synchronized (operation.cleanupLock) {
            @Nullable TaskExecutor cleanupExecutor;
            @Unmodifiable List<String> previousResidual;
            synchronized (stateLock) {
                cleanupExecutor = operation.cleanupExecutor;
                previousResidual = operation.residualResources;
            }
            if (cleanupExecutor == null) {
                if (previousResidual.isEmpty()) {
                    synchronized (stateLock) {
                        if (operation.status == Status.BLOCKED_RESIDUAL) {
                            restoreBeforeResidualLocked(operation);
                        }
                    }
                }
                return cleanupSnapshot(operation, previousResidual.isEmpty());
            }

            boolean reportedSuccess = false;
            @Nullable Throwable cleanupFailure = null;
            try {
                reportedSuccess = cleanupExecutor.retryResourceCleanup();
            } catch (RuntimeException | Error failure) {
                cleanupFailure = failure;
            }

            @Unmodifiable List<String> residual;
            try {
                residual = List.copyOf(cleanupExecutor.getResidualResources());
            } catch (RuntimeException | Error inspectionFailure) {
                residual = previousResidual;
                if (cleanupFailure == null) {
                    cleanupFailure = inspectionFailure;
                } else if (inspectionFailure != cleanupFailure) {
                    cleanupFailure.addSuppressed(inspectionFailure);
                }
            }
            boolean succeeded = reportedSuccess && residual.isEmpty();
            synchronized (stateLock) {
                // The per-operation monitor prevents concurrent retries; this identity check also protects against a
                // completion callback replacing the retained executor between the two state-lock sections.
                if (operation.cleanupExecutor == cleanupExecutor) {
                    operation.residualResources = residual;
                    if (succeeded) {
                        operation.cleanupExecutor = null;
                        // The cleanup pass has released the task-owned leases; do not retain the completed task graph.
                        if (operation.executor == cleanupExecutor) {
                            operation.executor = null;
                        }
                        restoreBeforeResidualLocked(operation);
                    } else {
                        operation.status = Status.BLOCKED_RESIDUAL;
                        addFailedStepLocked(operation, "resource_cleanup");
                        if (cleanupFailure != null) {
                            recordFailureLocked(operation, cleanupFailure);
                        }
                    }
                }
            }
            return cleanupSnapshot(operation, succeeded);
        }
    }

    /// Adds the cleanup outcome to an immutable operation snapshot.
    ///
    /// @param operation operation being inspected
    /// @param succeeded whether the executor reported success and no residual resources remain
    /// @return immutable cleanup result
    private @Unmodifiable Map<String, Object> cleanupSnapshot(Operation operation, boolean succeeded) {
        Map<String, Object> result = new LinkedHashMap<>(snapshot(operation));
        result.put("cleanup_succeeded", succeeded);
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    /// Stops accepting operations and requests cancellation of every active task.
    @Override
    public void close() {
        List<ExecutionReference> executions = new ArrayList<>();
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            completionListener = null;
            for (Operation operation : operations.values()) {
                if (!operation.status.terminal) {
                    operation.cancellationRequested = true;
                    if (operation.executor != null) {
                        executions.add(new ExecutionReference(operation, operation.executor));
                    }
                }
            }
        }
        for (ExecutionReference execution : executions) {
            cancelExecutor(execution.operation(), execution.executor());
        }
    }

    /// Returns and retains one known operation after pruning expired terminal entries.
    private Operation requireOperation(String operationId) {
        synchronized (stateLock) {
            pruneLocked();
            return requireOperationLocked(operationId);
        }
    }

    /// Resolves one known operation while the state lock is held.
    private Operation requireOperationLocked(String operationId) {
        @Nullable Operation operation = operations.get(Objects.requireNonNull(operationId, "operationId"));
        if (operation == null) {
            throw new IllegalArgumentException("Unknown or expired crash repair operation: " + operationId);
        }
        return operation;
    }

    /// Rejects new registrations after shutdown.
    private void requireOpenLocked() {
        if (closed) {
            throw new IllegalStateException("Crash repair operation registry is closed");
        }
    }

    /// Makes room for a new operation by evicting the oldest terminal entry.
    private void ensureCapacityLocked() {
        if (operations.size() < maximumOperations) {
            return;
        }
        @Nullable String terminalId = operations.entrySet().stream()
                .filter(entry -> entry.getValue().status.terminal
                        && canEvictTerminal(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        if (terminalId == null) {
            throw new IllegalStateException("Too many active crash repair operations");
        }
        operations.remove(terminalId);
    }

    /// Removes terminal snapshots whose retention period has elapsed.
    private void pruneLocked() {
        Instant cutoff = clock.instant().minus(terminalRetention);
        operations.values().removeIf(operation -> operation.finishedAt != null
                && !operation.finishedAt.isAfter(cutoff)
                && canEvictTerminal(operation));
    }

    /// Keeps operations whose task-owned lease cleanup still needs a bounded retry in this process.
    ///
    /// Evicting such an operation would leave the lock manager holding a residual lease without any public operation
    /// identifier through which the coordinator could call [#retryResourceCleanup(String)].
    private static boolean canEvictTerminal(Operation operation) {
        return !operation.ownerRetained
                && (operation.status != Status.BLOCKED_RESIDUAL || operation.residualResources.isEmpty());
    }

    /// Records a task-creation failure before an executor exists.
    private void failBeforeStart(Operation operation, Throwable failure) {
        synchronized (stateLock) {
            operation.status = Status.FAILED;
            recordFailureLocked(operation, failure);
            operation.failedSteps = List.of(operation.actionType);
            operation.finishedAt = clock.instant();
        }
    }

    /// Records an executor-start failure and detaches its listener.
    private void finishStartFailure(Operation operation, Throwable failure) {
        @Nullable Subscription subscription;
        synchronized (stateLock) {
            if (!operation.status.terminal) {
                operation.status = Status.FAILED;
                recordFailureLocked(operation, failure);
                operation.failedSteps = List.of(operation.actionType);
                operation.finishedAt = clock.instant();
            }
            if (operation.executor != null) {
                captureResidualLocked(operation, operation.executor);
            }
            operation.executor = null;
            subscription = operation.subscription;
            operation.subscription = null;
        }
        if (subscription != null) {
            try {
                subscription.unsubscribe();
            } catch (RuntimeException | Error unsubscribeFailure) {
                // The operation is already terminal. Keep its bounded snapshot and continue owner publication even
                // when a custom executor's subscription implementation fails during detachment.
                LOG.warning("Unable to detach crash repair operation listener " + operation.id, unsubscribeFailure);
            }
        }
    }

    /// Requests cancellation and converts cancellation-call failures into terminal operation failures.
    private void cancelExecutor(Operation operation, @Nullable TaskExecutor executor) {
        if (executor == null) {
            return;
        }
        try {
            executor.cancel();
        } catch (RuntimeException cancellationFailure) {
            finishStartFailure(operation, cancellationFailure);
        } catch (Error error) {
            finishStartFailure(operation, error);
            throw error;
        }
    }

    /// Publishes a JSON-safe immutable operation snapshot.
    private @Unmodifiable Map<String, Object> snapshot(Operation operation) {
        synchronized (stateLock) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("operation_id", operation.id);
            result.put("action_type", operation.actionType);
            result.put("status", operation.status.name());
            result.put("retryable", operation.status == Status.FAILED
                    || operation.status == Status.CANCELLED
                    || operation.status == Status.BLOCKED_RESIDUAL);
            result.put("cancellable", operation.cancellable && !operation.status.terminal);
            result.put("cancel_requested", operation.cancellationRequested);
            result.put("created_at", operation.createdAt.toString());
            if (operation.startedAt != null) {
                result.put("started_at", operation.startedAt.toString());
            }
            if (operation.finishedAt != null) {
                result.put("finished_at", operation.finishedAt.toString());
            }
            if (operation.failureType != null) {
                result.put("failure_type", operation.failureType);
                result.put("failure_message", Objects.requireNonNull(operation.failureMessage));
                result.put("failed_step", operation.failedSteps.isEmpty()
                        ? operation.actionType
                        : operation.failedSteps.get(0));
            }
            result.put("failed_steps", operation.failedSteps);
            result.put("completed_steps", completedStepNamesLocked(operation));
            if (!operation.failedSteps.isEmpty()) {
                result.put("resume_from_step", operation.failedSteps.get(0));
            }
            result.put("retained_completed_steps", operation.checkpoint.completedSteps());
            result.put("residual_resources", operation.residualResources);
            result.put("steps", stepSnapshotsLocked(operation));
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
        }
    }

    /// Publishes the ordered task lifecycle captured for one operation.
    ///
    /// @param operation operation whose state lock is held by the caller
    /// @return immutable per-task step snapshots
    private static @Unmodifiable List<@Unmodifiable Map<String, Object>> stepSnapshotsLocked(Operation operation) {
        List<@Unmodifiable Map<String, Object>> snapshots = new ArrayList<>();
        for (Step step : operation.stepOrder) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("name", step.name);
            snapshot.put("status", step.status.name());
            snapshots.add(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(snapshot)));
        }
        return List.copyOf(snapshots);
    }

    /// Returns successful task names in the order in which they were retained or observed.
    ///
    /// @param operation operation whose state lock is held by the caller
    /// @return immutable ordered completed-step names
    private static @Unmodifiable List<String> completedStepNamesLocked(Operation operation) {
        List<String> completed = new ArrayList<>(operation.checkpoint.completedSteps());
        for (Step step : operation.stepOrder) {
            if (step.status == StepStatus.SUCCEEDED && !completed.contains(step.name)) {
                completed.add(step.name);
            }
        }
        return List.copyOf(completed);
    }

    /// Captures task-owned cleanup diagnostics without taking ownership of task cleanup itself.
    private static void captureResidualLocked(Operation operation, TaskExecutor executor) {
        boolean firstResidualObservation = operation.status != Status.BLOCKED_RESIDUAL;
        if (firstResidualObservation) {
            operation.statusBeforeResidual = operation.status;
            operation.failureTypeBeforeResidual = operation.failureType;
            operation.failureMessageBeforeResidual = operation.failureMessage;
            operation.failedStepsBeforeResidual = operation.failedSteps;
        }
        @Unmodifiable List<String> residual;
        try {
            residual = List.copyOf(executor.getResidualResources());
        } catch (RuntimeException | Error inspectionFailure) {
            residual = List.of("resource_cleanup:inspection_failed");
            recordFailureLocked(operation, inspectionFailure);
            addFailedStepLocked(operation, "resource_cleanup");
        }
        if (residual.isEmpty()) {
            if (firstResidualObservation) {
                clearBeforeResidualLocked(operation);
            }
            return;
        }
        operation.residualResources = List.copyOf(residual);
        operation.cleanupExecutor = executor;
        operation.status = Status.BLOCKED_RESIDUAL;
        addFailedStepLocked(operation, "resource_cleanup");
    }

    /// Restores the operation snapshot captured immediately before a residual cleanup block.
    ///
    /// @param operation operation whose state lock is held by the caller
    private static void restoreBeforeResidualLocked(Operation operation) {
        operation.status = operation.statusBeforeResidual != null
                ? operation.statusBeforeResidual
                : Status.FAILED;
        operation.failureType = operation.failureTypeBeforeResidual;
        operation.failureMessage = operation.failureMessageBeforeResidual;
        operation.failedSteps = operation.failedStepsBeforeResidual;
        clearBeforeResidualLocked(operation);
    }

    /// Clears the temporary pre-residual snapshot after cleanup has either completed or proved unnecessary.
    ///
    /// @param operation operation whose state lock is held by the caller
    private static void clearBeforeResidualLocked(Operation operation) {
        operation.statusBeforeResidual = null;
        operation.failureTypeBeforeResidual = null;
        operation.failureMessageBeforeResidual = null;
        operation.failedStepsBeforeResidual = List.of();
    }

    /// Adds one failed step exactly once while the operation lock is held.
    ///
    /// @param operation operation receiving the step
    /// @param stepName stable step name
    private static void addFailedStepLocked(Operation operation, String stepName) {
        if (!operation.failedSteps.contains(stepName)) {
            List<String> updated = new ArrayList<>(operation.failedSteps);
            updated.add(stepName);
            operation.failedSteps = List.copyOf(updated);
        }
    }

    /// Captures one task lifecycle transition while the operation lock is held.
    ///
    /// @param operation operation receiving the transition
    /// @param task task whose lifecycle changed
    /// @param status new step status
    private static void updateStepLocked(Operation operation, Task<?> task, StepStatus status) {
        Step step = operation.steps.get(task);
        if (step == null) {
            step = new Step(boundedStepName(task.getName()));
            operation.steps.put(task, step);
            operation.stepOrder.add(step);
        }
        step.status = status;
    }

    /// Bounds and normalizes a task name before it crosses the MCP boundary.
    ///
    /// @param name task-provided name
    /// @return single-line bounded name
    private static String boundedStepName(String name) {
        String normalized = Objects.requireNonNull(name, "name")
                .replaceAll("[\\r\\n\\t]+", " ")
                .strip();
        return normalized.length() <= 256 ? normalized : normalized.substring(0, 256);
    }

    /// Converts a failure into a bounded single-line message.
    private static String failureMessage(Throwable failure) {
        String message = Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName())
                .replaceAll("[\\r\\n\\t]+", " ")
                .strip();
        return message.length() <= MAX_FAILURE_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_FAILURE_MESSAGE_LENGTH);
    }

    /// Stores only bounded public failure metadata so terminal entries do not retain exception object graphs.
    private static void recordFailureLocked(Operation operation, Throwable failure) {
        Throwable checked = Objects.requireNonNull(failure, "failure");
        operation.failureType = checked.getClass().getName();
        operation.failureMessage = failureMessage(checked);
    }

    /// Validates one strictly positive duration.
    private static Duration requirePositive(Duration duration, String name) {
        Duration checked = Objects.requireNonNull(duration, name);
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return checked;
    }

    /// Mutable operation state accessed only while holding [#stateLock].
    @NotNullByDefault
    private static final class Operation {
        /// Opaque public identifier.
        private final String id;

        /// Stable structured repair action type.
        private final String actionType;

        /// Whether MCP cancellation is supported for this operation.
        private final boolean cancellable;

        /// Registration timestamp.
        private final Instant createdAt;

        /// Progress retained from an earlier attempt.
        private final RepairCheckpoint checkpoint;

        /// Current lifecycle state.
        private Status status = Status.QUEUED;

        /// Whether cooperative cancellation has been accepted.
        private boolean cancellationRequested;

        /// Active task executor, or null outside execution.
        private @Nullable TaskExecutor executor;

        /// Completion listener registration, or null outside execution.
        private @Nullable Subscription subscription;

        /// First start timestamp, or null before startup.
        private @Nullable Instant startedAt;

        /// Terminal timestamp, or null while active.
        private @Nullable Instant finishedAt;

        /// Terminal failure type, or null for active, successful, and cancelled operations.
        private @Nullable String failureType;

        /// Bounded terminal failure message, or null when no failure was recorded.
        private @Nullable String failureMessage;

        /// Stable ordered failed step names.
        private @Unmodifiable List<String> failedSteps = List.of();

        /// Ordered task objects and their current lifecycle state, retained only while the operation is queryable.
        private final Map<Task<?>, Step> steps = new IdentityHashMap<>();

        /// First-observed task order used for deterministic status serialization.
        private final List<Step> stepOrder = new ArrayList<>();

        /// Terminal state that preceded a temporary residual-cleanup block.
        private @Nullable Status statusBeforeResidual;

        /// Failure type captured before a temporary residual-cleanup block.
        private @Nullable String failureTypeBeforeResidual;

        /// Failure message captured before a temporary residual-cleanup block.
        private @Nullable String failureMessageBeforeResidual;

        /// Failed-step list captured before a temporary residual-cleanup block.
        private @Unmodifiable List<String> failedStepsBeforeResidual = List.of();

        /// Resource descriptions left behind by task-owned cleanup.
        private @Unmodifiable List<String> residualResources = List.of();

        /// Whether an application coordinator still needs this terminal snapshot for a linked repair plan.
        private boolean ownerRetained;

        /// Whether the terminal callback has already been delivered for this operation.
        private boolean completionNotified;

        /// Executor retained solely to retry a residual lease cleanup, or null when clean.
        private @Nullable TaskExecutor cleanupExecutor;

        /// Serializes cleanup retries for this operation and prevents stale ABA updates.
        private final Object cleanupLock = new Object();

        /// Creates one queued operation.
        private Operation(
                String id,
                String actionType,
                boolean cancellable,
                Instant createdAt,
                RepairCheckpoint checkpoint) {
            this.id = Objects.requireNonNull(id, "id");
            this.actionType = Objects.requireNonNull(actionType, "actionType");
            this.cancellable = cancellable;
            this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
            this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        }
    }

    /// Maps one executor's lifecycle into its owning operation.
    @NotNullByDefault
    private final class CompletionListener extends TaskListener {
        /// Operation receiving executor lifecycle events.
        private final Operation operation;

        /// Creates a listener for one operation.
        private CompletionListener(Operation operation) {
            this.operation = Objects.requireNonNull(operation, "operation");
        }

        /// Records a task that has passed preparation and is ready to execute.
        @Override
        public void onReady(Task<?> task) {
            synchronized (stateLock) {
                if (!operation.status.terminal) {
                    updateStepLocked(operation, task, StepStatus.PREPARING);
                }
            }
        }

        /// Records the task currently executing its primary body.
        @Override
        public void onRunning(Task<?> task) {
            synchronized (stateLock) {
                if (!operation.status.terminal) {
                    updateStepLocked(operation, task, StepStatus.RUNNING);
                }
            }
        }

        /// Records a successfully completed task.
        @Override
        public void onFinished(Task<?> task) {
            synchronized (stateLock) {
                if (!operation.status.terminal) {
                    updateStepLocked(operation, task, StepStatus.SUCCEEDED);
                }
            }
        }

        /// Records the task that failed and preserves its exact failure step for retry diagnostics.
        @Override
        public void onFailed(Task<?> task, Throwable throwable) {
            synchronized (stateLock) {
                if (!operation.status.terminal) {
                    updateStepLocked(operation, task, isCancellation(throwable)
                            ? StepStatus.CANCELLED
                            : StepStatus.FAILED);
                    if (!isCancellation(throwable)) {
                        addFailedStepLocked(operation, boundedStepName(task.getName()));
                        recordFailureLocked(operation, throwable);
                    }
                }
            }
        }

        /// Marks the operation running before task work begins.
        @Override
        public void onStart() {
            synchronized (stateLock) {
                if (!operation.status.terminal) {
                    operation.status = Status.RUNNING;
                    operation.startedAt = clock.instant();
                }
            }
        }

        /// Selects exactly one terminal state and releases executor-owned references.
        @Override
        public void onStop(boolean success, TaskExecutor executor) {
            @Nullable Subscription subscription;
            synchronized (stateLock) {
                if (operation.status.terminal) {
                    return;
                }
                @Nullable Throwable terminalFailure;
                try {
                    terminalFailure = executor.getFailure();
                } catch (RuntimeException | Error failureInspection) {
                    // A custom executor must not be able to prevent the registry from committing a terminal state.
                    terminalFailure = failureInspection;
                }
                if (success) {
                    operation.status = Status.SUCCEEDED;
                } else if (isCancellation(terminalFailure)
                        || terminalFailure == null && operation.cancellationRequested) {
                    operation.status = Status.CANCELLED;
                } else {
                    operation.status = Status.FAILED;
                    recordFailureLocked(operation, terminalFailure != null
                            ? terminalFailure
                            : new IllegalStateException("Repair task failed without a cause"));
                }
                if (operation.status == Status.CANCELLED) {
                    for (Step step : operation.stepOrder) {
                        if (step.status != StepStatus.SUCCEEDED && step.status != StepStatus.FAILED) {
                            step.status = StepStatus.CANCELLED;
                        }
                    }
                } else if (operation.failureType != null && operation.failedSteps.isEmpty()) {
                    addFailedStepLocked(operation, operation.actionType);
                }
                if (success && operation.stepOrder.isEmpty()) {
                    operation.stepOrder.add(new Step(operation.actionType, StepStatus.SUCCEEDED));
                }
                captureResidualLocked(operation, executor);
                operation.finishedAt = clock.instant();
                if (operation.status != Status.BLOCKED_RESIDUAL) {
                    operation.executor = null;
                }
                subscription = operation.subscription;
                operation.subscription = null;
                // Step values remain as the bounded public snapshot; task keys would retain the completed graph.
                operation.steps.clear();
            }
            if (subscription != null) {
                try {
                    subscription.unsubscribe();
                } catch (RuntimeException | Error unsubscribeFailure) {
                    // Terminal state is already committed. Continue owner publication even when a custom
                    // subscription handle fails during cleanup.
                    LOG.warning("Unable to detach completed crash repair operation listener " + operation.id,
                            unsubscribeFailure);
                }
            }
            notifyCompletion(operation);
        }
    }

    /// Returns whether a terminal failure represents cooperative cancellation.
    private static boolean isCancellation(@Nullable Throwable failure) {
        return failure instanceof CancellationException || failure instanceof InterruptedException;
    }

    /// Internal operation state.
    @NotNullByDefault
    private enum Status {
        /// Registered before the executor starts.
        QUEUED(false),

        /// Executor has announced startup.
        RUNNING(false),

        /// Task chain completed successfully.
        SUCCEEDED(true),

        /// Task chain completed with a non-cancellation failure.
        FAILED(true),

        /// Task completed but its resource lease cleanup remains blocked and retryable.
        BLOCKED_RESIDUAL(true),

        /// Cooperative cancellation ended the task chain.
        CANCELLED(true);

        /// Whether no further lifecycle transition is allowed.
        private final boolean terminal;

        /// Creates one lifecycle value.
        Status(boolean terminal) {
            this.terminal = terminal;
        }
    }

    /// Lifecycle state of one observable task step.
    @NotNullByDefault
    private enum StepStatus {
        /// Task has been prepared and is waiting for its primary body.
        PREPARING,

        /// Task primary body is executing.
        RUNNING,

        /// Task completed successfully.
        SUCCEEDED,

        /// Task failed.
        FAILED,

        /// Task was cancelled before completion.
        CANCELLED
    }

    /// Mutable task-step snapshot retained by one operation.
    @NotNullByDefault
    private static final class Step {
        /// Bounded task display name.
        private final String name;

        /// Current lifecycle state.
        private StepStatus status;

        /// Creates a preparing step.
        private Step(String name) {
            this(name, StepStatus.PREPARING);
        }

        /// Creates a step with an explicit initial state.
        private Step(String name, StepStatus status) {
            this.name = Objects.requireNonNull(name, "name");
            this.status = Objects.requireNonNull(status, "status");
        }
    }

    /// Active executor paired with its owning operation for lock-free cancellation.
    ///
    /// @param operation owning operation
    /// @param executor executor to cancel
    @NotNullByDefault
    private record ExecutionReference(Operation operation, TaskExecutor executor) {
        /// Validates both references.
        private ExecutionReference {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(executor, "executor");
        }
    }
}
