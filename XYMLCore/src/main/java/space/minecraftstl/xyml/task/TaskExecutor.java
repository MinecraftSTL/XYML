/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
import space.minecraftstl.xyml.util.Lang;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/// Coordinates one task chain and publishes its lifecycle to registered listeners.
///
/// Listener delivery is synchronous on the task execution thread. Registrations and cancellations are thread-safe,
/// preserve registration order, and use snapshot delivery: a cancellation racing an already-started notification may
/// still receive that notification. Listener [RuntimeException] failures are reported to the current thread's
/// uncaught exception handler and do not interrupt later listeners or task execution. Listener [Error] failures are
/// rethrown unchanged.
@NotNullByDefault
public abstract class TaskExecutor {
    /// First task in the execution chain.
    protected final Task<?> firstTask;

    /// Internal thread-safe listener registry.
    private final TaskListenerRegistry taskListenerRegistry = new TaskListenerRegistry();

    /// Whether cancellation has been requested for this execution chain.
    protected volatile boolean cancelled = false;

    /// Invocation-local cancellation view exposed while a terminal listener is being notified.
    private final ThreadLocal<@Nullable Boolean> cancellationNotificationView = new ThreadLocal<>();

    /// Last task failure, or `null` when execution has not failed or was cancelled before recording a failure.
    protected @Nullable Exception exception;

    /// Terminal chain failure including [Error] values, or null before a failed terminal outcome is recorded.
    protected volatile @Nullable Throwable failure;

    /// Immutable stage metadata exposed to task-progress consumers.
    private final @Unmodifiable List<Task.StagesHint> hints;

    /// Optional stable presentation title installed by a UI presentation model before start.
    private volatile @Nullable String taskExecutionTitle;

    /// Whether executions created by this executor are eligible for user-visible history.
    private volatile boolean taskExecutionUserVisible;

    /// Creates an executor for the supplied first task.
    public TaskExecutor(Task<?> task) {
        this.firstTask = Objects.requireNonNull(task, "task");
        this.hints = task instanceof Task<?>.StagesHintTask hintTask
                ? List.copyOf(hintTask.getHints())
                : List.of();
    }

    /// Installs the stable top-level title used by the application task registry.
    ///
    /// The first non-empty title wins so repeated starts of one executor remain grouped under one presentation
    /// contract. This metadata does not affect task scheduling or resource ownership.
    ///
    /// @param title user-facing title
    /// @param userVisible whether the execution should appear in active and successful history
    public final void setTaskExecutionPresentation(String title, boolean userVisible) {
        Objects.requireNonNull(title, "title");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        synchronized (this) {
            if (taskExecutionTitle == null) {
                taskExecutionTitle = title;
            }
            taskExecutionUserVisible |= userVisible;
        }
    }

    /// Returns the top-level execution title, falling back to the root task name.
    final String taskExecutionTitle() {
        @Nullable String title = taskExecutionTitle;
        return title == null ? firstTask.getName() : title;
    }

    /// Returns whether this executor has an explicit user-visible presentation contract or a major root task.
    final boolean taskExecutionUserVisible() {
        return taskExecutionUserVisible || firstTask.getSignificance().shouldShow();
    }

    /// Registers a listener and returns a handle that removes only this registration.
    ///
    /// The same listener object may be registered repeatedly. Every returned [Subscription] controls one registration
    /// and can be cancelled independently.
    public Subscription subscribeTaskListener(TaskListener taskListener) {
        ListenerRegistration registration = new ListenerRegistration(taskListener);
        taskListenerRegistry.add(registration);
        return Subscription.create(() -> taskListenerRegistry.remove(registration));
    }

    /// Delivers one lifecycle action to the current listener snapshot.
    ///
    /// Runtime failures from one listener are reported and isolated by the registry; [Error] values are rethrown.
    ///
    /// @param action lifecycle action to invoke for each listener
    protected final void notifyTaskListeners(Consumer<? super TaskListener> action) {
        taskListenerRegistry.forEach(Objects.requireNonNull(action, "action"));
    }

    /// Returns whether this executor currently has at least one listener registration.
    ///
    /// @return true when a listener is registered
    protected final boolean hasTaskListeners() {
        return !taskListenerRegistry.isEmpty();
    }

    /// Returns the reason why task execution failed, or `null` if no failure has been recorded.
    @Nullable
    public Exception getException() {
        return exception;
    }

    /// Returns the complete terminal failure, including [Error], or null after success and before termination.
    public @Nullable Throwable getFailure() {
        @Nullable Throwable terminalFailure = failure;
        return terminalFailure != null ? terminalFailure : exception;
    }

    /// Starts this task execution chain asynchronously.
    public abstract TaskExecutor start();

    /// Starts this task execution chain and waits for its terminal result.
    public abstract boolean test();

    /// Requests cancellation of this execution chain and its remaining tasks.
    public abstract void cancel();

    /// Returns whether cancellation has been requested.
    public boolean isCancelled() {
        @Nullable Boolean invocationCancelled = cancellationNotificationView.get();
        return invocationCancelled == null ? cancelled : invocationCancelled;
    }

    /// Delivers one terminal listener notification with an invocation-local cancellation view.
    ///
    /// Repeated starts of one executor can have different terminal states. The view is therefore scoped to the
    /// synchronous callback instead of mutating the executor-wide legacy flag and making a sibling invocation appear
    /// cancelled.
    ///
    /// @param invocationCancelled whether the invocation being notified was cancelled
    /// @param action terminal listener action
    final void notifyTaskListenersForInvocation(
            boolean invocationCancelled,
            Consumer<? super TaskListener> action) {
        @Nullable Boolean previous = cancellationNotificationView.get();
        cancellationNotificationView.set(invocationCancelled);
        try {
            notifyTaskListeners(action);
        } finally {
            if (previous == null) {
                cancellationNotificationView.remove();
            } else {
                cancellationNotificationView.set(previous);
            }
        }
    }

    /// Returns a handle bound to this executor's exact top-level invocation.
    ///
    /// The default executor has no registry-backed aggregate and therefore returns an inert handle. Specialized
    /// executors may override this method with an invocation-scoped handle.
    ///
    /// @return exact invocation progress handle
    public TaskExecutionProgressHandle taskExecutionProgressHandle() {
        return TaskExecutionProgressHandle.unavailable();
    }

    /// Returns immutable stage metadata for progress presentation.
    public @Unmodifiable List<Task.StagesHint> getHints() {
        return hints;
    }

    /// Returns resources whose task lease cleanup failed and remains retryable in this process.
    ///
    /// Executors that do not use the built-in resource arbiter return an empty list. The returned descriptions are
    /// diagnostics only; callers must not infer ownership from them or perform duplicate cleanup.
    ///
    /// @return immutable residual resource descriptions
    public @Unmodifiable List<String> getResidualResources() {
        return List.of();
    }

    /// Performs one bounded retry of residual resource cleanup.
    ///
    /// @return true when no residual resources remain after the retry
    public boolean retryResourceCleanup() {
        return getResidualResources().isEmpty();
    }

    /// Stores independently cancellable listener registrations and isolates notification failures.
    @NotNullByDefault
    private static final class TaskListenerRegistry extends CopyOnWriteArrayList<TaskListener> {
        /// Serialization version for the listener registry.
        private static final long serialVersionUID = 1L;

        /// Invokes an action for every listener in the current notification snapshot.
        ///
        /// Listener [RuntimeException] failures are reported to the existing uncaught-exception handler before
        /// delivery continues with the next listener. Every [Error] is rethrown unchanged.
        @Override
        public void forEach(Consumer<? super TaskListener> action) {
            Objects.requireNonNull(action, "action");
            for (TaskListener listener : this) {
                try {
                    action.accept(listener);
                } catch (RuntimeException listenerFailure) {
                    reportListenerFailure(listenerFailure);
                }
            }
        }

        /// Reports a listener failure while isolating only [RuntimeException] failures from the reporting hook.
        private static void reportListenerFailure(RuntimeException listenerFailure) {
            try {
                Lang.handleUncaughtException(listenerFailure);
            } catch (RuntimeException ignored) {
                // A presentation listener and its reporting hook must not corrupt the task state machine.
            }
        }
    }

    /// Delegates one independently removable listener registration.
    @NotNullByDefault
    private static final class ListenerRegistration extends TaskListener {
        /// Listener owned by this exact registration.
        private final TaskListener delegate;

        /// Creates an independently removable listener occurrence.
        private ListenerRegistration(TaskListener delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /// Delegates the task-chain start event.
        @Override
        public void onStart() {
            delegate.onStart();
        }

        /// Delegates a task-ready event.
        @Override
        public void onReady(Task<?> task) {
            delegate.onReady(task);
        }

        /// Delegates a task-running event.
        @Override
        public void onRunning(Task<?> task) {
            delegate.onRunning(task);
        }

        /// Delegates a successful task-completion event.
        @Override
        public void onFinished(Task<?> task) {
            delegate.onFinished(task);
        }

        /// Delegates a failed task-completion event.
        @Override
        public void onFailed(Task<?> task, Throwable throwable) {
            delegate.onFailed(task, throwable);
        }

        /// Delegates the task-chain terminal event.
        @Override
        public void onStop(boolean success, TaskExecutor executor) {
            delegate.onStop(success, executor);
        }

        /// Delegates a task presentation-properties event.
        @Override
        public void onPropertiesUpdate(Task<?> task) {
            delegate.onPropertiesUpdate(task);
        }
    }
}
