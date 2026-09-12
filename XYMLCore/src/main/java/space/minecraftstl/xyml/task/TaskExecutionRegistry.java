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
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;

/// Application-session registry for top-level task executions and their actual task details.
///
/// The registry is toolkit-neutral. It receives immutable event data from [AsyncTaskExecutor], keeps terminal
/// history for the current process, and publishes a complete immutable snapshot after every accepted transition.
/// The default [#global()] instance is shared by the launcher UI; tests may construct an isolated registry.
@NotNullByDefault
public final class TaskExecutionRegistry {
    /// Maximum combined number of terminal records retained by one registry.
    public static final int TERMINAL_HISTORY_LIMIT = 256;

    /// Maximum UTF-8 payload retained by one execution's lifecycle log.
    private static final int LOG_LIMIT_BYTES = 64 * 1024;

    /// Shared application registry.
    private static final TaskExecutionRegistry GLOBAL = new TaskExecutionRegistry();

    /// Serializes mutable execution state and snapshot construction.
    private final Object stateLock = new Object();

    /// Preserves insertion order for stable UI ordering while executions are active.
    private final LinkedHashMap<UUID, MutableExecution> executions = new LinkedHashMap<>();

    /// Listener registrations are independent and safe to mutate during publication.
    private final CopyOnWriteArrayList<ListenerSlot> listeners = new CopyOnWriteArrayList<>();

    /// Revision-aware listeners used by asynchronous UI projections.
    private final CopyOnWriteArrayList<RevisionListenerSlot> revisionListeners = new CopyOnWriteArrayList<>();

    /// Monotonic publication revision guarded by [#stateLock].
    private long publicationRevision;

    /// Returns the process-wide task registry used by launcher executors.
    public static TaskExecutionRegistry global() {
        return GLOBAL;
    }

    /// Creates an empty isolated registry.
    public TaskExecutionRegistry() {
    }

    /// Registers a listener for complete immutable registry snapshots.
    ///
    /// The listener is called synchronously on the thread that publishes the task transition. UI callers should
    /// dispatch to their toolkit thread. A listener failure is isolated and cannot stop task execution.
    ///
    /// @param listener callback receiving all retained executions
    /// @return independently cancellable subscription
    public Subscription subscribe(Listener listener) {
        ListenerSlot slot = new ListenerSlot(listener);
        listeners.add(slot);
        return Subscription.create(() -> listeners.remove(slot));
    }

    /// Registers a listener that receives a monotonic publication revision with every snapshot.
    ///
    /// UI projections should discard a publication whose revision is older than the last rendered revision. This
    /// protects the initial synchronous render and event-dispatch-thread queues from late worker publications.
    ///
    /// @param listener callback receiving the publication revision and complete snapshots
    /// @return independently cancellable subscription
    public Subscription subscribeVersioned(RevisionListener listener) {
        RevisionListenerSlot slot = new RevisionListenerSlot(listener);
        revisionListeners.add(slot);
        return Subscription.create(() -> revisionListeners.remove(slot));
    }

    /// Returns a stable immutable snapshot of all active and retained terminal executions.
    public @Unmodifiable List<TaskExecutionSnapshot> snapshots() {
        synchronized (stateLock) {
            return snapshotLocked();
        }
    }

    /// Returns an atomic revision and snapshot pair for an initial UI render.
    public Publication publication() {
        synchronized (stateLock) {
            return new Publication(publicationRevision, snapshotLocked());
        }
    }

    /// Returns one execution snapshot, or null after it has not been registered or has been evicted.
    public @Nullable TaskExecutionSnapshot snapshot(UUID executionId) {
        Objects.requireNonNull(executionId, "executionId");
        synchronized (stateLock) {
            MutableExecution execution = executions.get(executionId);
            return execution == null ? null : execution.snapshotLocked();
        }
    }

    /// Requests cancellation for one top-level execution.
    ///
    /// The callback is invoked outside the registry lock. A false result means the execution is unknown or already
    /// terminal. The executor remains authoritative for actual cancellation and terminal classification.
    public boolean requestCancellation(UUID executionId) {
        Objects.requireNonNull(executionId, "executionId");
        @Nullable Runnable cancellation;
        synchronized (stateLock) {
            MutableExecution execution = executions.get(executionId);
            if (execution == null || !transitionToCancellingLocked(execution)) {
                return false;
            }
            cancellation = claimCancellationCallbackLocked(execution);
        }
        publish();
        if (cancellation != null) {
            try {
                cancellation.run();
            } catch (RuntimeException | Error failure) {
                recordTopLevelFailure(executionId, "cancel-failed", failure);
            }
        }
        return true;
    }

    /// Marks one execution as cancelling without invoking its executor callback.
    ///
    /// This is used by the legacy direct [AsyncTaskExecutor#cancel()] entry point. The callback must remain outside
    /// this method so a registry-originated cancellation can invoke the executor without recursively marking itself.
    ///
    /// @param executionId top-level execution ID
    /// @return whether this call changed the execution state
    boolean markCancellationRequested(UUID executionId) {
        Objects.requireNonNull(executionId, "executionId");
        synchronized (stateLock) {
            MutableExecution execution = executions.get(executionId);
            if (execution == null || !transitionToCancellingLocked(execution)) {
                return false;
            }
            // The executor that calls this package-private method performs the cancellation itself. Claiming the
            // callback prevents a concurrently arriving public request from dispatching the same cancellation twice.
            execution.cancellationCallbackClaimed = true;
        }
        publish();
        return true;
    }

    /// Transitions one non-terminal execution to the cancelling state while the registry lock is held.
    ///
    /// @param execution mutable execution to transition
    /// @return whether the state changed
    private boolean transitionToCancellingLocked(MutableExecution execution) {
        if (execution.status.isTerminal() || execution.cancellationRequested || !execution.cancelable) {
            return false;
        }
        execution.cancellationRequested = true;
        execution.status = TaskExecutionStatus.CANCELLING;
        execution.cancelable = false;
        execution.appendLogLocked(null, "cancel-requested", "Cancellation requested");
        return true;
    }

    /// Claims the callback for one cancellation transition while the registry lock is held.
    ///
    /// @param execution mutable execution whose callback may be claimed
    /// @return callback to invoke outside the registry lock, or null when it is not installed or already claimed
    private @Nullable Runnable claimCancellationCallbackLocked(MutableExecution execution) {
        if (execution.cancellationCallbackClaimed || execution.cancellation == null) {
            return null;
        }
        execution.cancellationCallbackClaimed = true;
        return execution.cancellation;
    }

    /// Starts one invocation record. This method is package-private because executors are the event authority.
    Execution begin(TaskExecutor executor, String title, boolean userVisible) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(title, "title");
        MutableExecution mutable = new MutableExecution(
                this,
                UUID.randomUUID(),
                title,
                userVisible,
                Instant.now());
        synchronized (stateLock) {
            executions.put(mutable.id, mutable);
            mutable.appendLogLocked(null, "waiting", "Task execution waiting");
        }
        publish();
        return new Execution(mutable);
    }

    /// Removes all records and invalidates late callbacks for the current application session.
    public void clear() {
        List<Subscription> subscriptions = new ArrayList<>();
        synchronized (stateLock) {
            for (MutableExecution execution : executions.values()) {
                subscriptions.addAll(execution.progressSubscriptions);
                execution.status = TaskExecutionStatus.CANCELLED;
                execution.cancelable = false;
                execution.cancellationRequested = true;
                execution.endedAt = Instant.now();
                execution.cancellation = null;
                execution.progressSubscriptions.clear();
                execution.tasks.clear();
                execution.taskOrder.clear();
                execution.logs.clear();
            }
            executions.clear();
        }
        subscriptions.forEach(Subscription::unsubscribe);
        publish();
    }

    /// Removes all records for isolated lifecycle tests.
    void clearForTests() {
        clear();
    }

    /// Publishes one complete snapshot to every current listener.
    private void publish() {
        Publication publication;
        synchronized (stateLock) {
            publication = new Publication(++publicationRevision, snapshotLocked());
        }
        for (ListenerSlot listener : listeners) {
            listener.notifySafely(publication.snapshots());
        }
        for (RevisionListenerSlot listener : revisionListeners) {
            listener.notifySafely(publication);
        }
    }

    /// Records a cancellation callback failure without changing the executor's terminal state.
    private void recordTopLevelFailure(UUID executionId, String event, Throwable failure) {
        synchronized (stateLock) {
            MutableExecution execution = executions.get(executionId);
            if (execution != null) {
                execution.appendLogLocked(null, event, renderThrowable(failure));
            }
        }
        publish();
    }

    /// Records a progress update from one task without retaining the task object after terminal state.
    private void progressChanged(MutableExecution execution, Task<?> task) {
        synchronized (stateLock) {
            if (execution.status.isTerminal()) {
                return;
            }
            MutableTask mutableTask = execution.tasks.get(task);
            if (mutableTask == null) {
                return;
            }
            mutableTask.progress = normalizedProgress(task.progressObservable().getValue());
            double weight = taskWeight(task);
            boolean weightChanged = mutableTask.weight != weight;
            mutableTask.weight = weight;
            if (mutableTask.status == TaskExecutionTaskStatus.RUNNING
                    && mutableTask.significance.shouldShow()) {
                mutableTask.contributesToAggregate = true;
            }
            if (shouldLogProgress(mutableTask.loggedProgress, mutableTask.progress)) {
                mutableTask.loggedProgress = mutableTask.progress;
                execution.appendLogLocked(
                        mutableTask.id,
                        "progress",
                        progressMessage(mutableTask.progress));
            }
            if (weightChanged) {
                execution.appendLogLocked(
                        mutableTask.id,
                        "updated",
                        propertiesMessage(mutableTask.stage, mutableTask.progress, weight));
            }
            execution.progress = execution.aggregateProgressLocked();
        }
        publish();
    }

    /// Builds snapshots while the mutable registry lock is held.
    private @Unmodifiable List<TaskExecutionSnapshot> snapshotLocked() {
        return executions.values().stream().map(MutableExecution::snapshotLocked).toList();
    }

    /// Removes the oldest terminal records after a new terminal transition.
    private void evictTerminalHistoryLocked() {
        List<MutableExecution> terminal = executions.values().stream()
                .filter(execution -> execution.status.isTerminal()
                        && !(execution.status == TaskExecutionStatus.SUCCEEDED && !execution.userVisible))
                .sorted(Comparator.comparing(execution ->
                        execution.endedAt == null ? Instant.MAX : execution.endedAt))
                .toList();
        int removeCount = terminal.size() - TERMINAL_HISTORY_LIMIT;
        for (int index = 0; index < removeCount; index++) {
            executions.remove(terminal.get(index).id);
        }
    }

    /// Listener callback receiving a complete registry snapshot.
    @FunctionalInterface
    public interface Listener {
        /// Handles one immutable snapshot publication.
        ///
        /// @param snapshots active and retained terminal records
        void onChanged(@Unmodifiable List<TaskExecutionSnapshot> snapshots);
    }

    /// Listener callback receiving a complete snapshot publication and its monotonic revision.
    @FunctionalInterface
    public interface RevisionListener {
        /// Handles one immutable versioned snapshot publication.
        ///
        /// @param publication revision and active/retained terminal records
        void onChanged(Publication publication);
    }

    /// Atomic registry publication used to order asynchronous UI updates.
    @NotNullByDefault
    public record Publication(long revision, @Unmodifiable List<TaskExecutionSnapshot> snapshots) {
        /// Validates and defensively copies one publication.
        public Publication {
            if (revision < 0L) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            Objects.requireNonNull(snapshots, "snapshots");
            snapshots = List.copyOf(snapshots);
        }
    }

    /// One invocation handle retained by an executor while its task graph is alive.
    @NotNullByDefault
    final class Execution {
        /// Mutable execution owned by this handle.
        private final MutableExecution mutable;

        /// Creates a handle for one registry-owned invocation.
        private Execution(MutableExecution mutable) {
            this.mutable = mutable;
        }

        /// Returns the stable invocation ID.
        UUID id() {
            return mutable.id;
        }

        /// Installs the invocation-scoped cancellation callback.
        void setCancellation(Runnable cancellation) {
            @Nullable Runnable runImmediately = installCancellation(cancellation);
            if (runImmediately != null) {
                runImmediately.run();
            }
        }

        /// Installs the invocation-scoped cancellation callback and returns a late callback claimed by this caller.
        ///
        /// The asynchronous executor uses this split form to run a callback outside its own monitor. Keeping the
        /// callback outside both locks lets an exceptional cancellation path converge through its normal terminal
        /// cleanup instead of leaving an invocation permanently in the cancelling state.
        ///
        /// @param cancellation callback that cancels the invocation's resource domain
        /// @return callback to invoke after the caller leaves its monitor, or null when no late callback is needed
        @Nullable Runnable installCancellation(Runnable cancellation) {
            Objects.requireNonNull(cancellation, "cancellation");
            @Nullable Runnable runImmediately = null;
            synchronized (stateLock) {
                if (mutable.status.isTerminal()) {
                    return null;
                }
                mutable.cancellation = cancellation;
                if (mutable.cancellationRequested) {
                    runImmediately = claimCancellationCallbackLocked(mutable);
                }
            }
            return runImmediately;
        }

        /// Returns whether cancellation has been requested for this invocation.
        boolean cancellationRequested() {
            synchronized (stateLock) {
                return mutable.cancellationRequested;
            }
        }

        /// Publishes the top-level start transition.
        void started() {
            synchronized (stateLock) {
                if (!mutable.status.isTerminal()) {
                    mutable.appendLogLocked(null, "started", "Task execution started");
                }
            }
            publish();
        }

        /// Publishes one actual task ready transition.
        void taskReady(@Nullable Task<?> parent, Task<?> task) {
            if (task.isTerminalCleanup()) {
                return;
            }
            updateTask(parent, task, TaskExecutionTaskStatus.WAITING, "waiting", null);
        }

        /// Publishes one actual task running transition.
        void taskRunning(@Nullable Task<?> parent, Task<?> task) {
            if (task.isTerminalCleanup()) {
                return;
            }
            updateTask(parent, task, TaskExecutionTaskStatus.RUNNING, "running", null);
            synchronized (stateLock) {
                if (!mutable.status.isTerminal() && !mutable.cancellationRequested) {
                    mutable.status = TaskExecutionStatus.RUNNING;
                }
            }
            publish();
        }

        /// Publishes one successful actual task transition.
        void taskFinished(@Nullable Task<?> parent, Task<?> task) {
            if (task.isTerminalCleanup()) {
                return;
            }
            updateTask(parent, task, TaskExecutionTaskStatus.SUCCEEDED, "finished", null);
        }

        /// Publishes one failed or cancelled actual task transition.
        void taskFailed(@Nullable Task<?> parent, Task<?> task, Throwable failure) {
            if (task.isTerminalCleanup()) {
                return;
            }
            TaskExecutionTaskStatus status = isCancellation(failure)
                    ? TaskExecutionTaskStatus.CANCELLED
                    : TaskExecutionTaskStatus.FAILED;
            updateTask(parent, task, status, status == TaskExecutionTaskStatus.CANCELLED ? "cancelled" : "failed", failure);
        }

        /// Publishes one task metadata/progress transition.
        void taskPropertiesUpdated(@Nullable Task<?> parent, Task<?> task) {
            if (task.isTerminalCleanup()) {
                return;
            }
            synchronized (stateLock) {
                if (mutable.status.isTerminal()) {
                    return;
                }
                MutableTask mutableTask = mutable.ensureTaskLocked(parent, task);
                @Nullable String stage = task.getStage() != null ? task.getStage() : task.getInheritedStage();
                OptionalDouble progress = normalizedProgress(task.progressObservable().getValue());
                double weight = taskWeight(task);
                boolean stageChanged = !Objects.equals(mutableTask.loggedStage, stage);
                boolean progressChanged = shouldLogProgress(mutableTask.loggedProgress, progress);
                boolean weightChanged = mutableTask.loggedWeight != weight;
                mutableTask.stage = stage;
                mutableTask.progress = progress;
                mutableTask.weight = weight;
                if (mutableTask.status == TaskExecutionTaskStatus.RUNNING
                        && mutableTask.significance.shouldShow()) {
                    mutableTask.contributesToAggregate = true;
                }
                if (stageChanged || progressChanged || weightChanged) {
                    mutableTask.loggedStage = stage;
                    mutableTask.loggedProgress = progress;
                    mutableTask.loggedWeight = weight;
                    mutable.appendLogLocked(
                            mutableTask.id,
                            "updated",
                            propertiesMessage(stage, progress, weight));
                }
                mutable.progress = mutable.aggregateProgressLocked();
            }
            publish();
        }

        /// Publishes the top-level terminal transition and releases live task subscriptions.
        TaskExecutionStatus stopped(boolean success, @Nullable Throwable failure) {
            List<Subscription> subscriptions;
            TaskExecutionStatus terminalStatus;
            synchronized (stateLock) {
                if (mutable.status.isTerminal()) {
                    return mutable.status;
                }
                mutable.endedAt = Instant.now();
                @Nullable Throwable effectiveFailure = failure;
                if (isCancellation(effectiveFailure)
                        || mutable.cancellationRequested && effectiveFailure == null) {
                    if (effectiveFailure == null) {
                        effectiveFailure = new CancellationException("Cancelled by user");
                    }
                    mutable.status = TaskExecutionStatus.CANCELLED;
                } else if (success) {
                    mutable.status = TaskExecutionStatus.SUCCEEDED;
                    mutable.progress = OptionalDouble.of(1.0D);
                    mutable.completedProgressWeight = mutable.totalProgressWeight;
                } else {
                    mutable.status = TaskExecutionStatus.FAILED;
                }
                terminalStatus = mutable.status;
                mutable.failure = effectiveFailure == null ? null : redact(renderThrowable(effectiveFailure));
                if (terminalStatus == TaskExecutionStatus.CANCELLED) {
                    markUnfinishedTasksCancelledLocked(mutable, effectiveFailure);
                } else if (terminalStatus == TaskExecutionStatus.FAILED) {
                    markUnfinishedTasksFailedLocked(mutable, effectiveFailure);
                }
                mutable.cancelable = false;
                mutable.appendLogLocked(
                        null,
                        mutable.status == TaskExecutionStatus.SUCCEEDED ? "succeeded" : "stopped",
                        effectiveFailure == null ? "Task execution stopped" : renderThrowable(effectiveFailure));
                subscriptions = List.copyOf(mutable.progressSubscriptions);
                mutable.progressSubscriptions.clear();
                mutable.tasks.clear();
                mutable.cancellation = null;
                if (mutable.status == TaskExecutionStatus.SUCCEEDED && !mutable.userVisible) {
                    executions.remove(mutable.id);
                }
                evictTerminalHistoryLocked();
            }
            subscriptions.forEach(Subscription::unsubscribe);
            publish();
            return terminalStatus;
        }

        /// Marks task rows that were still active when the top-level cancellation committed.
        ///
        /// A resource waiter can be cancelled before its future callback is delivered. Recording the terminal state
        /// here keeps the detail view truthful even when no later task callback arrives.
        ///
        /// @param execution mutable execution whose cancellation committed
        /// @param cancellationFailure effective cancellation signal
        private static void markUnfinishedTasksCancelledLocked(
                MutableExecution execution,
                @Nullable Throwable cancellationFailure) {
            String failureText = cancellationFailure == null
                    ? null
                    : redact(renderThrowable(cancellationFailure));
            for (MutableTask task : execution.taskOrder) {
                if (task.status == TaskExecutionTaskStatus.SUCCEEDED
                        || task.status == TaskExecutionTaskStatus.FAILED
                        || task.status == TaskExecutionTaskStatus.CANCELLED) {
                    continue;
                }
                task.status = TaskExecutionTaskStatus.CANCELLED;
                task.endedAt = execution.endedAt;
                task.failure = failureText;
                execution.appendLogLocked(task.id, "cancelled", "Task cancelled by user");
            }
        }

        /// Marks task rows that were still active when the top-level failure committed.
        ///
        /// Resource acquisition and executor infrastructure can fail before a task-specific failure callback is
        /// delivered. A terminal failed workflow must not leave those actual task rows indefinitely waiting or
        /// running in the detail view.
        ///
        /// @param execution mutable execution whose failure committed
        /// @param topLevelFailure effective terminal failure, or null when no cause was supplied
        private static void markUnfinishedTasksFailedLocked(
                MutableExecution execution,
                @Nullable Throwable topLevelFailure) {
            @Nullable String failureText = topLevelFailure == null
                    ? null
                    : redact(renderThrowable(topLevelFailure));
            String eventMessage = topLevelFailure == null
                    ? "Task failed with top-level workflow failure"
                    : "Task failed with top-level workflow failure: " + renderThrowable(topLevelFailure);
            for (MutableTask task : execution.taskOrder) {
                if (task.status == TaskExecutionTaskStatus.SUCCEEDED
                        || task.status == TaskExecutionTaskStatus.FAILED
                        || task.status == TaskExecutionTaskStatus.CANCELLED) {
                    continue;
                }
                task.status = TaskExecutionTaskStatus.FAILED;
                task.endedAt = execution.endedAt;
                task.failure = failureText;
                execution.appendLogLocked(task.id, "failed", eventMessage);
            }
        }

        /// Attaches a task progress listener and records one task lifecycle transition.
        private void updateTask(
                @Nullable Task<?> parent,
                Task<?> task,
                TaskExecutionTaskStatus status,
                String event,
                @Nullable Throwable failure) {
            synchronized (stateLock) {
                if (mutable.status.isTerminal()) {
                    return;
                }
                MutableTask mutableTask = mutable.ensureTaskLocked(parent, task);
                TaskExecutionTaskStatus previousStatus = mutableTask.status;
                @Nullable String previousFailure = mutableTask.failure;
                @Nullable String stage = task.getStage() != null ? task.getStage() : task.getInheritedStage();
                OptionalDouble progress = status == TaskExecutionTaskStatus.SUCCEEDED
                        ? OptionalDouble.of(1.0D)
                        : normalizedProgress(task.progressObservable().getValue());
                double weight = taskWeight(task);
                boolean stageChanged = !Objects.equals(mutableTask.loggedStage, stage);
                boolean progressChanged = shouldLogProgress(mutableTask.loggedProgress, progress);
                boolean weightChanged = mutableTask.loggedWeight != weight;
                mutableTask.status = status;
                mutableTask.stage = stage;
                mutableTask.progress = progress;
                mutableTask.weight = weight;
                if (status == TaskExecutionTaskStatus.SUCCEEDED
                        || status == TaskExecutionTaskStatus.FAILED
                        || status == TaskExecutionTaskStatus.CANCELLED) {
                    mutableTask.endedAt = Instant.now();
                } else {
                    mutableTask.endedAt = null;
                }
                mutableTask.failure = failure == null ? null : redact(renderThrowable(failure));
                boolean lifecycleChanged = !mutableTask.lifecycleInitialized
                        || previousStatus != status
                        || !Objects.equals(previousFailure, mutableTask.failure);
                mutableTask.lifecycleInitialized = true;
                if (status == TaskExecutionTaskStatus.RUNNING) {
                    mutable.everRunning = true;
                    if (mutableTask.significance.shouldShow()) {
                        mutableTask.contributesToAggregate = true;
                    }
                }
                if (lifecycleChanged) {
                    mutable.appendLogLocked(
                            mutableTask.id,
                            event,
                            failure == null ? task.getName() : task.getName() + ": " + renderThrowable(failure));
                }
                if (task.getSignificance().shouldShow()) {
                    mutable.userVisible = true;
                }
                if (stageChanged || progressChanged || weightChanged) {
                    mutableTask.loggedStage = stage;
                    mutableTask.loggedProgress = progress;
                    mutableTask.loggedWeight = weight;
                    mutable.appendLogLocked(
                            mutableTask.id,
                            "updated",
                            propertiesMessage(stage, progress, weight));
                }
                mutable.progress = mutable.aggregateProgressLocked();
            }
            publish();
        }

    }

    /// Mutable invocation state confined by [#stateLock].
    @NotNullByDefault
    private static final class MutableExecution {
        /// Owning registry.
        private final TaskExecutionRegistry registry;

        /// Stable invocation ID.
        private final UUID id;

        /// Top-level title.
        private final String title;

        /// Start timestamp.
        private final Instant startedAt;

        /// Actual task map using object identity because reusable tasks may share equal fields.
        private final IdentityHashMap<Task<?>, MutableTask> tasks = new IdentityHashMap<>();

        /// Stable insertion order for deterministic detail snapshots while retaining identity lookup above.
        private final List<MutableTask> taskOrder = new ArrayList<>();

        /// Lifecycle log entries in chronological order.
        private final List<TaskExecutionLogEntry> logs = new ArrayList<>();

        /// Live progress subscriptions released at terminal state.
        private final List<Subscription> progressSubscriptions = new ArrayList<>();

        /// Current aggregate state.
        private TaskExecutionStatus status = TaskExecutionStatus.WAITING;

        /// Current aggregate progress.
        private OptionalDouble progress = OptionalDouble.empty();

        /// Whether at least one actual task has entered the running state in this invocation.
        private boolean everRunning;

        /// Total work weight represented by tasks that entered this execution's aggregate contribution set.
        private double totalProgressWeight;

        /// Completed work weight represented by the aggregate contribution set.
        private double completedProgressWeight;

        /// Whether active/success presentation is allowed.
        private boolean userVisible;

        /// Whether cancellation remains accepted.
        private boolean cancelable = true;

        /// Whether cancellation was requested through the registry.
        private boolean cancellationRequested;

        /// Terminal timestamp.
        private @Nullable Instant endedAt;

        /// Redacted terminal failure details.
        private @Nullable String failure;

        /// Executor cancellation callback, installed after begin.
        private @Nullable Runnable cancellation;

        /// Whether the one cancellation callback has already been claimed by a request path.
        private boolean cancellationCallbackClaimed;

        /// Creates one mutable invocation state.
        private MutableExecution(
                TaskExecutionRegistry registry,
                UUID id,
                String title,
                boolean userVisible,
                Instant startedAt) {
            this.registry = registry;
            this.id = id;
            this.title = title;
            this.userVisible = userVisible;
            this.startedAt = startedAt;
        }

        /// Returns or creates one mutable task row and subscribes to progress exactly once.
        private MutableTask ensureTaskLocked(@Nullable Task<?> parent, Task<?> task) {
            MutableTask existing = tasks.get(task);
            if (existing != null) {
                return existing;
            }
            @Nullable MutableTask mutableParent = parent == null ? null : tasks.get(parent);
            if (parent != null && mutableParent == null) {
                // A child callback can race a delayed parent callback. Register the parent first so the flattened
                // detail list still preserves the actual task tree instead of silently losing its parent edge.
                mutableParent = ensureTaskLocked(null, parent);
            }
            MutableTask created = new MutableTask(
                    UUID.randomUUID(),
                    mutableParent == null ? null : mutableParent.id,
                    task.getName(),
                    task.getStage() != null ? task.getStage() : task.getInheritedStage(),
                    task.getSignificance(),
                    Instant.now(),
                    normalizedProgress(task.progressObservable().getValue()),
                    taskWeight(task));
            tasks.put(task, created);
            taskOrder.add(created);
            created.loggedStage = created.stage;
            created.loggedProgress = created.progress;
            created.loggedWeight = created.weight;
            Subscription progressSubscription = task.progressObservable().subscribe(
                    ignored -> registry.progressChanged(this, task));
            progressSubscriptions.add(progressSubscription);
            return created;
        }

        /// Computes aggregate progress from visible tasks without inventing progress for unknown tasks.
        private OptionalDouble aggregateProgressLocked() {
            double weightedProgress = 0.0D;
            double totalWeight = 0.0D;
            for (MutableTask task : taskOrder) {
                if (!task.contributesToAggregate) {
                    continue;
                }
                totalWeight += task.weight;
                weightedProgress += task.weight
                        * task.progress.orElseGet(
                                () -> task.status == TaskExecutionTaskStatus.SUCCEEDED ? 1.0D : 0.0D);
            }
            totalProgressWeight = totalWeight;
            completedProgressWeight = weightedProgress;
            return totalWeight == 0.0D
                    ? OptionalDouble.empty()
                    : OptionalDouble.of(weightedProgress / totalWeight);
        }

        /// Appends one redacted event while enforcing the byte cap.
        private void appendLogLocked(@Nullable UUID taskId, String event, String message) {
            String redacted = redact(message);
            TaskExecutionLogEntry entry = new TaskExecutionLogEntry(Instant.now(), taskId, event, redacted);
            logs.add(entry);
            trimLogsLocked();
        }

        /// Keeps the opening event and newest entries under the fixed UTF-8 cap.
        private void trimLogsLocked() {
            int bytes = logs.stream().mapToInt(MutableExecution::logBytes).sum();
            while (bytes > LOG_LIMIT_BYTES && logs.size() > 2) {
                TaskExecutionLogEntry removed = logs.remove(1);
                bytes -= logBytes(removed);
            }
            if (bytes <= LOG_LIMIT_BYTES || logs.isEmpty()) {
                return;
            }
            int entryBudget = Math.max(0, LOG_LIMIT_BYTES / logs.size() - 64);
            for (int index = 0; index < logs.size(); index++) {
                TaskExecutionLogEntry entry = logs.get(index);
                String message = truncateUtf8(entry.message(), entryBudget);
                logs.set(index, new TaskExecutionLogEntry(
                        entry.timestamp(),
                        entry.taskId(),
                        entry.event(),
                        message));
            }
        }

        /// Builds an immutable aggregate snapshot while holding the registry lock.
        private TaskExecutionSnapshot snapshotLocked() {
            List<TaskExecutionTaskSnapshot> taskSnapshots = taskOrder.stream()
                    .map(task -> task.snapshot(logs))
                    .toList();
            return new TaskExecutionSnapshot(
                    id,
                    title,
                    status,
                    progress,
                    totalProgressWeight,
                    completedProgressWeight,
                    everRunning,
                    userVisible,
                    cancelable,
                    startedAt,
                    endedAt,
                    failure,
                    taskSnapshots,
                    List.copyOf(logs));
        }

        /// Computes one log entry's UTF-8 storage cost.
        private static int logBytes(TaskExecutionLogEntry entry) {
            return entry.event().getBytes(StandardCharsets.UTF_8).length
                    + entry.message().getBytes(StandardCharsets.UTF_8).length + 64;
        }

        /// Truncates one log message without splitting a UTF-8 code point.
        private static String truncateUtf8(String message, int maximumBytes) {
            if (message.getBytes(StandardCharsets.UTF_8).length <= maximumBytes) {
                return message;
            }
            if (maximumBytes <= 3) {
                return "";
            }
            int contentBudget = Math.max(0, maximumBytes - 3);
            StringBuilder result = new StringBuilder();
            int bytes = 0;
            for (int offset = 0; offset < message.length();) {
                int codePoint = message.codePointAt(offset);
                int codePointBytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
                if (bytes + codePointBytes > contentBudget) {
                    break;
                }
                result.appendCodePoint(codePoint);
                bytes += codePointBytes;
                offset += Character.charCount(codePoint);
            }
            return result + "...";
        }
    }

    /// Mutable actual-task state confined by the registry lock.
    @NotNullByDefault
    private static final class MutableTask {
        /// Stable task ID.
        private final UUID id;

        /// Parent task ID, or null for root.
        private final @Nullable UUID parentId;

        /// Task title captured before task reuse can mutate it.
        private final String name;

        /// Resolved stage.
        private @Nullable String stage;

        /// Visibility significance.
        private final Task.TaskSignificance significance;

        /// First lifecycle timestamp.
        private final Instant startedAt;

        /// Current task state.
        private TaskExecutionTaskStatus status = TaskExecutionTaskStatus.WAITING;

        /// Whether the first lifecycle event has been recorded for this task.
        private boolean lifecycleInitialized;

        /// Current task progress.
        private OptionalDouble progress;

        /// Aggregate weight, initially one and replaced by a valid `total` property.
        private double weight;

        /// Whether this visible task has entered the execution-level aggregate contribution set.
        private boolean contributesToAggregate;

        /// Last progress value included in a detail log event.
        private OptionalDouble loggedProgress = OptionalDouble.empty();

        /// Last stage included in a detail log event.
        private @Nullable String loggedStage;

        /// Last aggregate weight included in a detail log event.
        private double loggedWeight;

        /// Terminal timestamp.
        private @Nullable Instant endedAt;

        /// Redacted task-level failure details.
        private @Nullable String failure;

        /// Creates one mutable task state.
        private MutableTask(
                UUID id,
                @Nullable UUID parentId,
                String name,
                @Nullable String stage,
                Task.TaskSignificance significance,
                Instant startedAt,
                OptionalDouble progress,
                double weight) {
            this.id = id;
            this.parentId = parentId;
            this.name = name;
            this.stage = stage;
            this.significance = significance;
            this.startedAt = startedAt;
            this.progress = progress;
            this.weight = weight;
        }

        /// Converts mutable state to an immutable task snapshot.
        private TaskExecutionTaskSnapshot snapshot(@Unmodifiable List<TaskExecutionLogEntry> allLogs) {
            @Unmodifiable List<TaskExecutionLogEntry> taskLogs = allLogs.stream()
                    .filter(entry -> id.equals(entry.taskId()))
                    .toList();
            return new TaskExecutionTaskSnapshot(
                    id,
                    parentId,
                    name,
                    stage,
                    significance,
                    status,
                    progress,
                    startedAt,
                    endedAt,
                    failure,
                    taskLogs);
        }
    }

    /// Owns one listener registration and isolates callback failures.
    @NotNullByDefault
    private static final class ListenerSlot {
        /// Registered callback.
        private final Listener listener;

        /// Creates one listener slot.
        private ListenerSlot(Listener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
        }

        /// Delivers one snapshot while isolating runtime failures.
        private void notifySafely(@Unmodifiable List<TaskExecutionSnapshot> snapshots) {
            try {
                listener.onChanged(snapshots);
            } catch (RuntimeException | Error ignored) {
                // A UI observer must never change task execution semantics.
            }
        }
    }

    /// Owns one revision-aware listener registration and isolates callback failures.
    @NotNullByDefault
    private static final class RevisionListenerSlot {
        /// Registered callback.
        private final RevisionListener listener;

        /// Creates one revision-aware listener slot.
        private RevisionListenerSlot(RevisionListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
        }

        /// Delivers one versioned publication while isolating runtime failures.
        private void notifySafely(Publication publication) {
            try {
                listener.onChanged(publication);
            } catch (RuntimeException | Error ignored) {
                // A UI observer must never change task execution semantics.
            }
        }
    }

    /// Normalizes a task progress source into the public optional contract.
    private static OptionalDouble normalizedProgress(@Nullable Double value) {
        if (value == null || !Double.isFinite(value) || value < 0.0D || value > 1.0D) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(value);
    }

    /// Returns whether a progress transition is meaningful enough for the bounded task log.
    private static boolean shouldLogProgress(OptionalDouble previous, OptionalDouble current) {
        if (previous.isPresent() != current.isPresent()) {
            return true;
        }
        if (previous.isEmpty()) {
            return false;
        }
        double oldValue = previous.getAsDouble();
        double newValue = current.getAsDouble();
        return Math.abs(newValue - oldValue) >= 0.05D
                || newValue == 0.0D
                || newValue == 1.0D;
    }

    /// Formats one compact progress event for a task detail timeline.
    private static String progressMessage(OptionalDouble progress) {
        return progress.isPresent()
                ? "progress=" + Math.round(progress.getAsDouble() * 100.0D) + "%"
                : "progress=unknown";
    }

    /// Formats one compact stage, progress, and aggregate-weight update.
    private static String propertiesMessage(
            @Nullable String stage,
            OptionalDouble progress,
            double weight) {
        return "stage=" + (stage == null || stage.isBlank() ? "unknown" : stage)
                + "; " + progressMessage(progress)
                + "; weight=" + weight;
    }

    /// Resolves one task's aggregate weight from its optional progress metadata.
    ///
    /// A task starts with one unit of weight. A finite non-negative numeric `total` replaces that unit with
    /// `max(1, total)`; repeated property notifications therefore replace the weight instead of accumulating it.
    private static double taskWeight(Task<?> task) {
        @Nullable Map<String, Object> properties = task.properties;
        if (properties != null) {
            Object total = properties.get("total");
            if (total instanceof Number number) {
                double value = number.doubleValue();
                if (Double.isFinite(value) && value >= 0.0D) {
                    return Math.max(1.0D, value);
                }
            }
        }
        @Nullable Long progressTotal = task.getProgressTotal();
        return progressTotal == null ? 1.0D : Math.max(1.0D, progressTotal.doubleValue());
    }

    /// Returns whether a failure denotes cancellation.
    private static boolean isCancellation(@Nullable Throwable failure) {
        return failure instanceof CancellationException || failure instanceof InterruptedException;
    }

    /// Renders and redacts a throwable without retaining the throwable object.
    private static String renderThrowable(Throwable failure) {
        try {
            return redact(StringUtils.getStackTrace(failure));
        } catch (RuntimeException | Error ignored) {
            return redact(String.valueOf(failure));
        }
    }

    /// Redacts configured access tokens from one user-visible message.
    private static String redact(String message) {
        return Logger.filterForbiddenToken(Objects.requireNonNull(message, "message"));
    }
}
