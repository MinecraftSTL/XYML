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
import space.minecraftstl.xyml.game.analyzer.RepairTaskFactory;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;

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

    /// Insertion-ordered operation state indexed by opaque identifier.
    private final Map<String, Operation> operations = new LinkedHashMap<>();

    /// Whether this registry no longer accepts new operations.
    private boolean closed;

    /// Creates a production registry with bounded terminal-state retention.
    public McpTaskOperationRegistry() {
        this(Clock.systemUTC(), Duration.ofMinutes(30), 256);
    }

    /// Creates a registry with explicit time and capacity policies.
    ///
    /// @param clock timestamp source
    /// @param terminalRetention duration for which terminal snapshots remain queryable
    /// @param maximumOperations maximum retained operation count
    public McpTaskOperationRegistry(Clock clock, Duration terminalRetention, int maximumOperations) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.terminalRetention = requirePositive(terminalRetention, "terminalRetention");
        if (maximumOperations <= 0) {
            throw new IllegalArgumentException("maximumOperations must be positive");
        }
        this.maximumOperations = maximumOperations;
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
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(taskFactory, "taskFactory");

        Operation operation;
        synchronized (stateLock) {
            requireOpenLocked();
            pruneLocked();
            ensureCapacityLocked();
            operation = new Operation(UUID.randomUUID().toString(), actionType, cancellable, clock.instant());
            operations.put(operation.id, operation);
        }

        final Task<?> task;
        final TaskExecutor executor;
        final Subscription subscription;
        try {
            task = Objects.requireNonNull(taskFactory.createTask(), "repair task factory returned null");
            executor = task.executor();
            subscription = executor.subscribeTaskListener(new CompletionListener(operation));
        } catch (RuntimeException creationFailure) {
            failBeforeStart(operation, creationFailure);
            return snapshot(operation);
        } catch (Error error) {
            failBeforeStart(operation, error);
            throw error;
        }

        boolean shouldStart;
        try {
            synchronized (stateLock) {
                shouldStart = !closed && !operation.status.terminal;
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
            return snapshot(operation);
        } catch (Error error) {
            finishStartFailure(operation, error);
            throw error;
        }
        if (!shouldStart) {
            subscription.unsubscribe();
        }
        return snapshot(operation);
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
        return Map.copyOf(result);
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
                .filter(entry -> entry.getValue().status.terminal)
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
                && !operation.finishedAt.isAfter(cutoff));
    }

    /// Records a task-creation failure before an executor exists.
    private void failBeforeStart(Operation operation, Throwable failure) {
        synchronized (stateLock) {
            operation.status = Status.FAILED;
            recordFailureLocked(operation, failure);
            operation.finishedAt = clock.instant();
        }
    }

    /// Records an executor-start failure and detaches its listener.
    private void finishStartFailure(Operation operation, Throwable failure) {
        @Nullable Subscription subscription;
        synchronized (stateLock) {
            operation.status = Status.FAILED;
            recordFailureLocked(operation, failure);
            operation.finishedAt = clock.instant();
            operation.executor = null;
            subscription = operation.subscription;
            operation.subscription = null;
        }
        if (subscription != null) {
            subscription.unsubscribe();
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
            }
            return Map.copyOf(result);
        }
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

        /// Creates one queued operation.
        private Operation(String id, String actionType, boolean cancellable, Instant createdAt) {
            this.id = Objects.requireNonNull(id, "id");
            this.actionType = Objects.requireNonNull(actionType, "actionType");
            this.cancellable = cancellable;
            this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
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
                @Nullable Throwable terminalFailure = executor.getFailure();
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
                operation.finishedAt = clock.instant();
                operation.executor = null;
                subscription = operation.subscription;
                operation.subscription = null;
            }
            if (subscription != null) {
                subscription.unsubscribe();
            }
        }
    }

    /// Returns whether a terminal failure represents cooperative cancellation.
    private static boolean isCancellation(@Nullable Throwable failure) {
        return failure instanceof CancellationException || failure instanceof InterruptedException;
    }

    /// Internal operation state.
    private enum Status {
        /// Registered before the executor starts.
        QUEUED(false),

        /// Executor has announced startup.
        RUNNING(false),

        /// Task chain completed successfully.
        SUCCEEDED(true),

        /// Task chain completed with a non-cancellation failure.
        FAILED(true),

        /// Cooperative cancellation ended the task chain.
        CANCELLED(true);

        /// Whether no further lifecycle transition is allowed.
        private final boolean terminal;

        /// Creates one lifecycle value.
        Status(boolean terminal) {
            this.terminal = terminal;
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
