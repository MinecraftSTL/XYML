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
package space.minecraftstl.xyml.task.presentation;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Adapts one task executor to a toolkit-neutral, single-surface presentation model.
///
/// The model must be created before the executor starts so no lifecycle event is missed. The stable title and
/// waiting phase are supplied by the caller, allowing the composition root to provide localized workflow text.
/// While the chain is active, the phase and progress belong to the most recently activated task whose significance
/// is visible to users. Concurrent visible tasks retain independent progress subscriptions, so completing the
/// current task falls back to the newest task that is still active.
///
/// Task and progress events are published synchronously on their originating worker threads. A task-level failure
/// records diagnostic details but does not make the model terminal because a non-relying task chain may recover.
/// The executor's stop event alone decides whether the complete workflow succeeded, failed, or was cancelled.
@NotNullByDefault
public final class TaskExecutorPresentationModel implements TaskPresentationModel, AutoCloseable {
    /// Lock serializing lifecycle events, progress events, subscriptions, cancellation, and closure.
    private final Object stateLock = new Object();

    /// Executor whose lifecycle is represented by this model.
    private final TaskExecutor executor;

    /// Stable caller-provided workflow title.
    private final String title;

    /// Caller-provided phase shown before a named visible task becomes active and for blank task names.
    private final String waitingPhase;

    /// Snapshot listeners isolated per registration so one failed presentation surface cannot block another.
    private final CopyOnWriteArrayList<SnapshotListenerSlot> snapshotListeners = new CopyOnWriteArrayList<>();

    /// Visible tasks that have become ready and have not yet finished or failed.
    private final List<ActiveTask> activeTasks = new ArrayList<>();

    /// Owned executor-listener registration.
    private final Subscription executorSubscription;

    /// Most recently published immutable state.
    private volatile TaskSnapshot currentSnapshot;

    /// Visible task currently supplying phase and progress, or null when no visible task remains active.
    private @Nullable ActiveTask currentTask;

    /// Most recent task failure, or null when the chain has not reported a failure.
    private @Nullable Throwable lastFailure;

    /// Whether one cancellation request has already been forwarded to the executor.
    private boolean cancellationRequested;

    /// Whether owned task and progress registrations have been released.
    private boolean closed;

    /// Creates an adapter for an executor that has not started yet.
    ///
    /// @param executor task executor to observe and cancel
    /// @param title stable localized workflow title
    /// @param waitingPhase localized phase used before work and as a blank task-name fallback
    public TaskExecutorPresentationModel(TaskExecutor executor, String title, String waitingPhase) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.title = Objects.requireNonNull(title, "title");
        this.waitingPhase = Objects.requireNonNull(waitingPhase, "waitingPhase");
        currentSnapshot = new TaskSnapshot(
                title,
                waitingPhase,
                OptionalDouble.empty(),
                TaskStatus.WAITING,
                false,
                "");
        executorSubscription = executor.subscribeTaskListener(new ExecutorListener());
    }

    /// Returns the latest immutable state.
    @Override
    public TaskSnapshot snapshot() {
        return currentSnapshot;
    }

    /// Registers a listener for future worker-thread snapshot transitions.
    ///
    /// @param listener listener invoked synchronously by the publishing thread
    /// @return registration controlling only this listener occurrence
    @Override
    public Subscription subscribe(ValueChangeListener<TaskSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (stateLock) {
            requireOpen();
            SnapshotListenerSlot slot = new SnapshotListenerSlot(listener);
            snapshotListeners.add(slot);
            return Subscription.create(() -> snapshotListeners.remove(slot));
        }
    }

    /// Forwards at most one permitted cancellation request to the executor.
    ///
    /// Cancellation does not become a terminal presentation state until the executor stops. Requests made while the
    /// model is waiting, terminal, already cancelled, or closed have no effect.
    @Override
    public void requestCancellation() {
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            if (closed
                    || cancellationRequested
                    || currentSnapshot.status().isTerminal()
                    || !currentSnapshot.cancelable()) {
                return;
            }

            cancellationRequested = true;
            transition = replaceSnapshotLocked(copyCurrent(
                    currentSnapshot.phase(),
                    currentSnapshot.progress(),
                    currentSnapshot.status(),
                    false,
                    currentSnapshot.details()));
        }

        publishTransition(transition);
        try {
            executor.cancel();
        } catch (RuntimeException | Error cancellationFailure) {
            rollbackRejectedCancellation();
            throw cancellationFailure;
        }
    }

    /// Releases the executor listener and every active task-progress listener exactly once.
    @Override
    public void close() {
        List<Subscription> progressSubscriptions = new ArrayList<>();
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            detachActiveTasksLocked(progressSubscriptions);
        }
        executorSubscription.unsubscribe();
        progressSubscriptions.forEach(Subscription::unsubscribe);
    }

    /// Applies the executor-chain start event without enabling cancellation before a task is ready.
    private void executorStarted() {
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            if (closed || currentSnapshot.status().isTerminal()) {
                return;
            }
            transition = replaceSnapshotLocked(new TaskSnapshot(
                    title,
                    waitingPhase,
                    OptionalDouble.empty(),
                    TaskStatus.WAITING,
                    false,
                    ""));
        }
        publishTransition(transition);
    }

    /// Activates a ready task and exposes its phase and progress when it is user-visible.
    ///
    /// @param task task entering its ready state
    private void taskReady(Task<?> task) {
        Objects.requireNonNull(task, "task");
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            if (closed || currentSnapshot.status().isTerminal()) {
                return;
            }

            @Nullable ActiveTask activeTask = activateVisibleTaskLocked(task);
            if (activeTask != null) {
                currentTask = activeTask;
                transition = replaceSnapshotLocked(copyCurrent(
                        phaseOf(task),
                        progressOf(task),
                        TaskStatus.RUNNING,
                        !cancellationRequested,
                        ""));
            } else {
                transition = replaceSnapshotLocked(copyCurrent(
                        currentSnapshot.phase(),
                        currentSnapshot.progress(),
                        TaskStatus.RUNNING,
                        !cancellationRequested,
                        currentSnapshot.details()));
            }
        }
        publishTransition(transition);
    }

    /// Makes a running visible task the current presentation phase.
    ///
    /// @param task task beginning its execution body
    private void taskRunning(Task<?> task) {
        taskReady(task);
    }

    /// Removes a completed task and publishes its completion or the latest still-active fallback task.
    ///
    /// @param task task that completed successfully
    private void taskFinished(Task<?> task) {
        Objects.requireNonNull(task, "task");
        @Nullable Subscription progressSubscription = null;
        @Nullable SnapshotTransition transition = null;
        synchronized (stateLock) {
            if (closed || currentSnapshot.status().isTerminal()) {
                return;
            }

            @Nullable ActiveTask removedTask = removeActiveTaskLocked(task);
            if (removedTask != null) {
                progressSubscription = removedTask.progressSubscription();
                if (currentTask == removedTask) {
                    @Nullable ActiveTask fallbackTask = latestActiveTaskLocked();
                    currentTask = fallbackTask;
                    if (fallbackTask == null) {
                        transition = replaceSnapshotLocked(copyCurrent(
                                phaseOf(task),
                                OptionalDouble.of(1.0),
                                TaskStatus.RUNNING,
                                !cancellationRequested,
                                ""));
                    } else {
                        transition = replaceSnapshotLocked(copyCurrent(
                                phaseOf(fallbackTask.task()),
                                progressOf(fallbackTask.task()),
                                TaskStatus.RUNNING,
                                !cancellationRequested,
                                ""));
                    }
                }
            }
        }
        publishTransition(transition);
        if (progressSubscription != null) {
            progressSubscription.unsubscribe();
        }
    }

    /// Records one task failure while leaving the complete workflow nonterminal until the executor stops.
    ///
    /// @param task task that failed
    /// @param failure task failure or cancellation signal
    private void taskFailed(Task<?> task, Throwable failure) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(failure, "failure");
        @Nullable Subscription progressSubscription = null;
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            if (closed || currentSnapshot.status().isTerminal()) {
                return;
            }

            lastFailure = failure;
            @Nullable ActiveTask removedTask = removeActiveTaskLocked(task);
            if (removedTask != null) {
                progressSubscription = removedTask.progressSubscription();
            }
            boolean wasCurrentTask = removedTask != null && currentTask == removedTask;
            if (wasCurrentTask) {
                currentTask = latestActiveTaskLocked();
            }

            String phase = wasCurrentTask && currentTask != null
                    ? phaseOf(currentTask.task())
                    : wasCurrentTask || currentTask == null
                            ? phaseOf(task)
                            : currentSnapshot.phase();
            OptionalDouble progress = wasCurrentTask && currentTask != null
                    ? progressOf(currentTask.task())
                    : wasCurrentTask ? progressOf(task) : currentSnapshot.progress();
            transition = replaceSnapshotLocked(copyCurrent(
                    phase,
                    progress,
                    TaskStatus.RUNNING,
                    !cancellationRequested,
                    isCancellation(failure) ? "" : formatFailureDetails(failure)));
        }
        publishTransition(transition);
        if (progressSubscription != null) {
            progressSubscription.unsubscribe();
        }
    }

    /// Refreshes phase and progress when the current task reports mutable presentation properties.
    ///
    /// @param task task whose properties changed
    private void taskPropertiesUpdated(Task<?> task) {
        Objects.requireNonNull(task, "task");
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            if (closed || currentTask == null || currentTask.task() != task) {
                return;
            }
            transition = replaceSnapshotLocked(copyCurrent(
                    phaseOf(task),
                    progressOf(task),
                    currentSnapshot.status(),
                    currentSnapshot.cancelable(),
                    currentSnapshot.details()));
        }
        publishTransition(transition);
    }

    /// Converts the executor's final result into the sole terminal model transition.
    ///
    /// @param succeeded whether the complete task chain succeeded
    /// @param sourceExecutor executor included in the callback
    private void executorStopped(boolean succeeded, TaskExecutor sourceExecutor) {
        Objects.requireNonNull(sourceExecutor, "sourceExecutor");
        List<Subscription> progressSubscriptions = new ArrayList<>();
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            if (closed || currentSnapshot.status().isTerminal()) {
                return;
            }

            detachActiveTasksLocked(progressSubscriptions);
            @Nullable Throwable executorFailure = sourceExecutor.getFailure();
            @Nullable Throwable terminalFailure = executorFailure != null
                    ? executorFailure
                    : lastFailure;
            boolean cancelled = !succeeded && (isCancellation(terminalFailure)
                    || sourceExecutor.isCancelled() && terminalFailure == null);
            TaskStatus terminalStatus = succeeded
                    ? TaskStatus.SUCCEEDED
                    : cancelled ? TaskStatus.CANCELLED : TaskStatus.FAILED;
            OptionalDouble terminalProgress = succeeded
                    ? OptionalDouble.of(1.0)
                    : currentSnapshot.progress();
            String details = terminalStatus == TaskStatus.FAILED && terminalFailure != null
                    ? formatFailureDetails(terminalFailure)
                    : "";
            transition = replaceSnapshotLocked(copyCurrent(
                    currentSnapshot.phase(),
                    terminalProgress,
                    terminalStatus,
                    false,
                    details));
        }
        publishTransition(transition);
        progressSubscriptions.forEach(Subscription::unsubscribe);
    }

    /// Publishes progress only when it belongs to the current active task.
    ///
    /// @param task task owning the progress source
    /// @param change progress transition that invalidated the task's current value
    private void taskProgressChanged(Task<?> task, ValueChange<Double> change) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(change, "change");
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            if (closed || currentTask == null || currentTask.task() != task) {
                return;
            }
            transition = replaceSnapshotLocked(copyCurrent(
                    currentSnapshot.phase(),
                    progressOf(task),
                    currentSnapshot.status(),
                    currentSnapshot.cancelable(),
                    currentSnapshot.details()));
        }
        publishTransition(transition);
    }

    /// Adds or returns one visible active task and its owned progress registration.
    ///
    /// @param task task entering an active lifecycle state
    /// @return active task registration, or null when the task is intentionally hidden
    private @Nullable ActiveTask activateVisibleTaskLocked(Task<?> task) {
        if (!task.getSignificance().shouldShow()) {
            return null;
        }

        @Nullable ActiveTask existingTask = findActiveTaskLocked(task);
        if (existingTask != null) {
            activeTasks.remove(existingTask);
            activeTasks.add(existingTask);
            return existingTask;
        }

        Subscription progressSubscription = task.progressObservable().subscribe(
                change -> taskProgressChanged(task, change));
        ActiveTask activeTask = new ActiveTask(task, progressSubscription);
        activeTasks.add(activeTask);
        return activeTask;
    }

    /// Finds a visible active task by object identity.
    ///
    /// @param task task instance to find
    /// @return matching active registration, or null when the task is not active
    private @Nullable ActiveTask findActiveTaskLocked(Task<?> task) {
        for (ActiveTask activeTask : activeTasks) {
            if (activeTask.task() == task) {
                return activeTask;
            }
        }
        return null;
    }

    /// Removes and unsubscribes one visible active task by object identity.
    ///
    /// @param task task instance to remove
    /// @return removed registration, or null when the task was not visible and active
    private @Nullable ActiveTask removeActiveTaskLocked(Task<?> task) {
        for (int index = activeTasks.size() - 1; index >= 0; index--) {
            ActiveTask activeTask = activeTasks.get(index);
            if (activeTask.task() == task) {
                activeTasks.remove(index);
                return activeTask;
            }
        }
        return null;
    }

    /// Returns the newest visible task that remains active.
    ///
    /// @return newest active task, or null when none remains
    private @Nullable ActiveTask latestActiveTaskLocked() {
        return activeTasks.isEmpty() ? null : activeTasks.get(activeTasks.size() - 1);
    }

    /// Moves every active progress registration to a caller-owned list and clears the current task.
    ///
    /// @param detachedSubscriptions mutable list receiving subscriptions for lock-free cancellation
    private void detachActiveTasksLocked(List<Subscription> detachedSubscriptions) {
        for (ActiveTask activeTask : activeTasks) {
            detachedSubscriptions.add(activeTask.progressSubscription());
        }
        activeTasks.clear();
        currentTask = null;
    }

    /// Resolves a nonblank task phase without exposing null or blank legacy names.
    ///
    /// @param task task supplying a possibly blank name
    /// @return task name or the explicit waiting-phase fallback
    private String phaseOf(Task<?> task) {
        String taskName = task.getName();
        return taskName.isBlank() ? waitingPhase : taskName;
    }

    /// Reads and validates a task's optional normalized progress.
    ///
    /// @param task task whose current progress is needed
    /// @return normalized progress or an empty value for the task's unknown sentinel
    private static OptionalDouble progressOf(Task<?> task) {
        return normalizedProgress(task.progressObservable().getValue());
    }

    /// Converts a nullable numeric source value to the presentation progress contract.
    ///
    /// @param value numeric source value, or null for an absent source value
    /// @return a finite zero-through-one value, otherwise empty
    private static OptionalDouble normalizedProgress(@Nullable Double value) {
        if (value == null || !Double.isFinite(value) || value < 0.0 || value > 1.0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(value);
    }

    /// Returns whether a failure describes cancellation rather than a workflow fault.
    ///
    /// @param failure failure to classify, or null when no failure was recorded
    /// @return true for cancellation and interruption signals
    private static boolean isCancellation(@Nullable Throwable failure) {
        return failure instanceof CancellationException || failure instanceof InterruptedException;
    }

    /// Formats diagnostic details without allowing optional presentation work to interrupt lifecycle delivery.
    ///
    /// Custom throwable implementations may fail while rendering their stack trace. The executor remains the
    /// authoritative failure source, so presentation falls back to empty details instead of blocking later listeners.
    ///
    /// @param failure failure whose stack trace should be shown
    /// @return rendered stack trace, or an empty string when rendering fails
    private static String formatFailureDetails(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        try {
            return StringUtils.getStackTrace(failure);
        } catch (RuntimeException | Error ignored) {
            return "";
        }
    }

    /// Creates a snapshot with the stable title and supplied mutable presentation fields.
    ///
    /// @param phase current phase
    /// @param progress optional normalized progress
    /// @param status lifecycle status
    /// @param cancelable whether cancellation is currently accepted
    /// @param details explanatory or diagnostic details
    /// @return immutable replacement snapshot
    private TaskSnapshot copyCurrent(
            String phase,
            OptionalDouble progress,
            TaskStatus status,
            boolean cancelable,
            String details) {
        return new TaskSnapshot(title, phase, progress, status, cancelable, details);
    }

    /// Replaces the current snapshot and captures a distinct immutable transition for lock-free delivery.
    ///
    /// @param replacement immutable replacement state
    /// @return captured transition, or null when the replacement equals the current state
    private @Nullable SnapshotTransition replaceSnapshotLocked(TaskSnapshot replacement) {
        TaskSnapshot previous = currentSnapshot;
        currentSnapshot = replacement;
        if (previous.equals(replacement)) {
            return null;
        }
        return new SnapshotTransition(previous, replacement);
    }

    /// Delivers one transition synchronously on its originating event thread.
    ///
    /// Concurrent event threads may interleave listener calls; each transition still carries its exact committed
    /// values, and toolkit consumers should dispatch and read [#snapshot()] before updating their UI.
    ///
    /// @param transition committed transition, or null when state did not change
    private void publishTransition(@Nullable SnapshotTransition transition) {
        if (transition == null) {
            return;
        }
        ValueChange<TaskSnapshot> change = new ValueChange<>(
                this,
                transition.previous(),
                transition.current());
        for (SnapshotListenerSlot listener : snapshotListeners) {
            listener.notifySafely(change);
        }
    }

    /// Restores cancellation availability when the executor rejects a cancellation request without accepting it.
    private void rollbackRejectedCancellation() {
        @Nullable SnapshotTransition transition = null;
        synchronized (stateLock) {
            if (!closed
                    && cancellationRequested
                    && !currentSnapshot.status().isTerminal()
                    && !executor.isCancelled()) {
                cancellationRequested = false;
                transition = replaceSnapshotLocked(copyCurrent(
                        currentSnapshot.phase(),
                        currentSnapshot.progress(),
                        currentSnapshot.status(),
                        true,
                        currentSnapshot.details()));
            }
        }
        publishTransition(transition);
    }

    /// Rejects subscriptions after owned event registrations have been released.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Task executor presentation model is closed");
        }
    }

    /// Owns one visible task and its progress subscription.
    ///
    /// @param task visible active task
    /// @param progressSubscription subscription to the task's neutral progress
    @NotNullByDefault
    private record ActiveTask(Task<?> task, Subscription progressSubscription) {
        /// Validates one active task registration.
        private ActiveTask {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(progressSubscription, "progressSubscription");
        }
    }

    /// One immutable transition captured for synchronous lock-free listener delivery.
    ///
    /// @param previous state before the transition
    /// @param current state after the transition
    @NotNullByDefault
    private record SnapshotTransition(TaskSnapshot previous, TaskSnapshot current) {
        /// Validates one captured transition.
        private SnapshotTransition {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(current, "current");
        }
    }

    /// Owns one independently removable and runtime-failure-isolated snapshot listener registration.
    @NotNullByDefault
    private static final class SnapshotListenerSlot {
        /// Listener owned by this exact registration.
        private final ValueChangeListener<TaskSnapshot> listener;

        /// Creates one snapshot listener registration.
        private SnapshotListenerSlot(ValueChangeListener<TaskSnapshot> listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
        }

        /// Delivers one change without allowing a runtime failure to block later listeners.
        private void notifySafely(ValueChange<TaskSnapshot> change) {
            try {
                listener.onChange(change);
            } catch (RuntimeException listenerFailure) {
                LOG.warning("A task presentation listener failed", listenerFailure);
            }
        }
    }

    /// Receives executor callbacks and delegates them to serialized model transitions.
    @NotNullByDefault
    private final class ExecutorListener extends TaskListener {
        /// Applies the executor start event.
        @Override
        public void onStart() {
            executorStarted();
        }

        /// Applies a task ready event.
        ///
        /// @param task task entering its ready state
        @Override
        public void onReady(Task<?> task) {
            taskReady(task);
        }

        /// Applies a task running event.
        ///
        /// @param task task beginning its execution body
        @Override
        public void onRunning(Task<?> task) {
            taskRunning(task);
        }

        /// Applies a successful task completion event.
        ///
        /// @param task task that completed
        @Override
        public void onFinished(Task<?> task) {
            taskFinished(task);
        }

        /// Applies a failed task completion event without finalizing the complete chain.
        ///
        /// @param task task that failed
        /// @param throwable failure or cancellation signal
        @Override
        public void onFailed(Task<?> task, Throwable throwable) {
            taskFailed(task, throwable);
        }

        /// Applies the complete chain's terminal result.
        ///
        /// @param success whether every required task succeeded
        /// @param sourceExecutor executor that stopped
        @Override
        public void onStop(boolean success, TaskExecutor sourceExecutor) {
            executorStopped(success, sourceExecutor);
        }

        /// Refreshes phase and progress after a current task property update.
        ///
        /// @param task task whose properties changed
        @Override
        public void onPropertiesUpdate(Task<?> task) {
            taskPropertiesUpdated(task);
        }
    }
}
