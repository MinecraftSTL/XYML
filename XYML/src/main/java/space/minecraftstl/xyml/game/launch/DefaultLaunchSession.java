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
package space.minecraftstl.xyml.game.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.observable.property.ReadOnlyProperty;
import space.minecraftstl.xyml.observable.property.SimpleObjectProperty;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.task.presentation.TaskExecutorPresentationModel;
import space.minecraftstl.xyml.task.presentation.TaskSnapshot;
import space.minecraftstl.xyml.task.presentation.TaskStatus;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.platform.ManagedProcess;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Runs one launch task and releases its owner's single-flight slot at the first terminal preparation state.
///
/// The session creates and owns a [TaskExecutorPresentationModel] before executor startup, then republishes that
/// model through its stable [LaunchSession] identity. This session intentionally has no operation that stops a
/// [ManagedProcess]. Once a process exists, ownership passes to the launcher process-monitoring layer.
@NotNullByDefault
public final class DefaultLaunchSession implements LaunchSession {
    /// Serializes startup, cancellation, and terminal-state selection.
    private final Object stateLock = new Object();

    /// Stable request captured before any asynchronous preparation begins.
    private final LaunchRequest request;

    /// Factory used exactly once unless cancellation wins before factory invocation.
    private final LaunchTaskFactory taskFactory;

    /// Explicit caller-provided task presentation title.
    private final String presentationTitle;

    /// Explicit caller-provided phase before task activation.
    private final String waitingPhase;

    /// Callback that releases this exact session from the owning service's preparation slot.
    private final Consumer<DefaultLaunchSession> preparationFinished;

    /// Toolkit-neutral observable launch lifecycle status.
    private final SimpleObjectProperty<LaunchStatus> statusProperty =
            new SimpleObjectProperty<>(this, "status", LaunchStatus.PREPARING);

    /// Stable presentation transition publisher used across executor adapter creation and closure.
    private final ValueChangeSupport<TaskSnapshot> presentationChanges = new ValueChangeSupport<>(this);

    /// Completion carrying the created process or the terminal preparation outcome.
    private final CompletableFuture<ManagedProcess> completion = new CompletableFuture<>();

    /// Latest launch lifecycle status, independent of presentation-listener behavior.
    private volatile LaunchStatus status = LaunchStatus.PREPARING;

    /// Latest immutable task presentation snapshot.
    private volatile TaskSnapshot presentationSnapshot;

    /// Created process, or null until and unless preparation produces one.
    private volatile @Nullable ManagedProcess createdProcess;

    /// Non-cancellation terminal failure, or null before and after other outcomes.
    private volatile @Nullable Throwable failure;

    /// Executor after task construction, or null while the factory is running or after terminal cleanup.
    private @Nullable TaskExecutor executor;

    /// Owned completion-listener subscription, or null outside active executor preparation.
    private @Nullable Subscription executorSubscription;

    /// Owned bridge from the executor presentation adapter, or null outside active executor preparation.
    private @Nullable Subscription presentationSubscription;

    /// Executor presentation adapter created before start, or null before creation and after terminal cleanup.
    private @Nullable TaskExecutorPresentationModel executorPresentation;

    /// Whether startup has claimed its only allowed invocation.
    private boolean started;

    /// Whether [TaskExecutor#start()] is running outside the state lock.
    private boolean executorStartPending;

    /// Whether cooperative cancellation has already been requested.
    private boolean cancellationRequested;

    /// Creates a service-owned session that has not started factory invocation yet.
    ///
    /// @param request immutable captured launch request
    /// @param taskFactory launch task factory
    /// @param presentationTitle explicit localized task-surface title
    /// @param waitingPhase explicit localized phase before task activation
    /// @param preparationFinished callback releasing this exact session from the service slot
    DefaultLaunchSession(
            LaunchRequest request,
            LaunchTaskFactory taskFactory,
            String presentationTitle,
            String waitingPhase,
            Consumer<DefaultLaunchSession> preparationFinished) {
        this.request = Objects.requireNonNull(request, "request");
        this.taskFactory = Objects.requireNonNull(taskFactory, "taskFactory");
        this.presentationTitle = Objects.requireNonNull(presentationTitle, "presentationTitle");
        this.waitingPhase = Objects.requireNonNull(waitingPhase, "waitingPhase");
        this.preparationFinished = Objects.requireNonNull(preparationFinished, "preparationFinished");
        presentationSnapshot = new TaskSnapshot(
                presentationTitle,
                waitingPhase,
                OptionalDouble.empty(),
                TaskStatus.WAITING,
                false,
                "");
    }

    /// Starts factory invocation and task execution at most once on the caller-supplied preparation executor.
    ///
    /// The executor presentation adapter and both executor listeners are installed before [TaskExecutor#start()].
    /// Executor startup itself runs without the session lock; [#executorStartPending] transfers cancellation safely
    /// across that boundary. Runtime factory failures become failed sessions. Errors release the service slot before
    /// being rethrown unchanged.
    void start() {
        synchronized (stateLock) {
            if (started || status != LaunchStatus.PREPARING) {
                return;
            }
            started = true;
        }

        Task<ManagedProcess> task;
        try {
            task = Objects.requireNonNull(taskFactory.create(request), "launch task factory returned null");
        } catch (RuntimeException exception) {
            finish(LaunchStatus.FAILED, null, exception);
            return;
        } catch (Error error) {
            failAndRethrow(error);
            return;
        }

        synchronized (stateLock) {
            if (status != LaunchStatus.PREPARING) {
                return;
            }
        }

        PreparedExecution preparedExecution;
        try {
            preparedExecution = prepareExecution(task);
        } catch (RuntimeException exception) {
            finish(LaunchStatus.FAILED, null, exception);
            return;
        } catch (Error error) {
            failAndRethrow(error);
            return;
        }

        boolean installed;
        synchronized (stateLock) {
            installed = status == LaunchStatus.PREPARING;
            if (installed) {
                executor = preparedExecution.executor();
                executorSubscription = preparedExecution.completionSubscription();
                presentationSubscription = preparedExecution.presentationSubscription();
                executorPresentation = preparedExecution.presentation();
                executorStartPending = true;
            }
        }

        if (!installed) {
            reportCleanupFailure(releasePreparedExecution(preparedExecution));
            return;
        }

        try {
            preparedExecution.executor().start();
        } catch (RuntimeException exception) {
            markExecutorStartCompleted(preparedExecution.executor());
            finish(LaunchStatus.FAILED, null, exception);
            return;
        } catch (Error error) {
            markExecutorStartCompleted(preparedExecution.executor());
            failAndRethrow(error);
            return;
        }

        @Nullable TaskExecutor executorToCancel = markExecutorStartCompleted(preparedExecution.executor());
        if (executorToCancel != null) {
            try {
                executorToCancel.cancel();
            } catch (RuntimeException exception) {
                finish(LaunchStatus.FAILED, null, exception);
            } catch (Error error) {
                failAndRethrow(error);
            }
        }
    }

    /// Fails a session whose preparation executor rejected or could not schedule startup.
    ///
    /// @param schedulingFailure scheduling failure to expose through the session
    void failBeforeStart(Throwable schedulingFailure) {
        finish(LaunchStatus.FAILED, null, Objects.requireNonNull(schedulingFailure, "schedulingFailure"));
    }

    /// Transitions to failure before rethrowing an execution error unchanged.
    ///
    /// A cleanup failure cannot replace the primary error; it is retained as a suppressed diagnostic instead.
    ///
    /// @param primaryError error raised by factory, executor startup, or cancellation
    private void failAndRethrow(Error primaryError) {
        try {
            finish(LaunchStatus.FAILED, null, primaryError);
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != primaryError) {
                primaryError.addSuppressed(cleanupFailure);
            }
        }
        throw primaryError;
    }

    /// Creates all executor-owned adapters and subscriptions before task execution starts.
    ///
    /// The authoritative completion listener is registered before the presentation model's executor listener. This
    /// guarantees the session reaches a terminal state and releases its single-flight slot before an [Error] from a
    /// downstream terminal-presentation observer can interrupt the executor listener snapshot. Partial construction
    /// failures release every resource already created before being rethrown.
    ///
    /// @param task root launch task
    /// @return complete prepared execution bundle
    private PreparedExecution prepareExecution(Task<ManagedProcess> task) {
        TaskExecutor taskExecutor = task.executor();
        @Nullable Subscription taskCompletionSubscription = null;
        @Nullable TaskExecutorPresentationModel taskPresentation = null;
        @Nullable Subscription taskPresentationSubscription = null;
        try {
            taskCompletionSubscription = taskExecutor.subscribeTaskListener(new CompletionListener(task));
            TaskExecutorPresentationModel createdPresentation =
                    new TaskExecutorPresentationModel(taskExecutor, presentationTitle, waitingPhase);
            taskPresentation = createdPresentation;
            taskPresentationSubscription = createdPresentation.subscribe(
                    ignored -> presentationChanged(createdPresentation));
            return new PreparedExecution(
                    taskExecutor,
                    createdPresentation,
                    taskPresentationSubscription,
                    taskCompletionSubscription);
        } catch (RuntimeException | Error creationFailure) {
            @Nullable Throwable cleanupFailure = releaseDetachedExecution(
                    taskCompletionSubscription,
                    taskPresentationSubscription,
                    taskPresentation);
            if (cleanupFailure != null && cleanupFailure != creationFailure) {
                creationFailure.addSuppressed(cleanupFailure);
            }
            throw creationFailure;
        }
    }

    /// Releases a prepared execution that lost the race against an already-terminal session.
    ///
    /// @param preparedExecution detached execution bundle
    /// @return first cleanup failure, or null
    private static @Nullable Throwable releasePreparedExecution(PreparedExecution preparedExecution) {
        return releaseDetachedExecution(
                preparedExecution.completionSubscription(),
                preparedExecution.presentationSubscription(),
                preparedExecution.presentation());
    }

    /// Releases detached subscriptions and their presentation adapter in dependency order.
    ///
    /// @param completionSubscription completion listener, or null before installation
    /// @param taskPresentationSubscription session presentation bridge, or null before installation
    /// @param taskPresentation executor presentation adapter, or null before creation
    /// @return first cleanup failure with later failures suppressed, or null
    private static @Nullable Throwable releaseDetachedExecution(
            @Nullable Subscription completionSubscription,
            @Nullable Subscription taskPresentationSubscription,
            @Nullable TaskExecutorPresentationModel taskPresentation) {
        @Nullable Throwable cleanupFailure = null;
        cleanupFailure = attempt(cleanupFailure, () -> unsubscribe(completionSubscription));
        cleanupFailure = attempt(cleanupFailure, () -> unsubscribe(taskPresentationSubscription));
        cleanupFailure = attempt(cleanupFailure, () -> closePresentation(taskPresentation));
        return cleanupFailure;
    }

    /// Returns the stable captured launch request.
    @Override
    public LaunchRequest request() {
        return request;
    }

    /// Returns the latest launch lifecycle status.
    @Override
    public LaunchStatus status() {
        return status;
    }

    /// Returns the toolkit-neutral observable launch status.
    @Override
    public ReadOnlyProperty<LaunchStatus> statusProperty() {
        return statusProperty;
    }

    /// Returns the latest stable task-presentation snapshot.
    @Override
    public TaskSnapshot snapshot() {
        return presentationSnapshot;
    }

    /// Registers a task-presentation listener against the session identity.
    @Override
    public Subscription subscribe(ValueChangeListener<TaskSnapshot> listener) {
        return presentationChanges.subscribe(Objects.requireNonNull(listener, "listener"));
    }

    /// Requests cancellation through the same idempotent session state machine as [#cancel()].
    @Override
    public void requestCancellation() {
        cancel();
    }

    /// Returns a minimal completion-stage view that does not expose this session's mutable future methods.
    @Override
    public CompletionStage<ManagedProcess> completion() {
        return completion.minimalCompletionStage();
    }

    /// Returns the managed process after successful creation.
    @Override
    public Optional<ManagedProcess> createdProcess() {
        return Optional.ofNullable(createdProcess);
    }

    /// Returns a recorded non-cancellation failure.
    @Override
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    /// Requests cancellation only while process preparation is active.
    @Override
    public boolean cancel() {
        @Nullable TaskExecutor executorToCancel;
        boolean finishWithoutExecutor;
        @Nullable PresentationTransition cancellationTransition;
        synchronized (stateLock) {
            if (status != LaunchStatus.PREPARING || cancellationRequested) {
                return false;
            }
            cancellationRequested = true;
            cancellationTransition = replacePresentationSnapshotLocked(withCancellationDisabled(
                    presentationSnapshot));
            executorToCancel = executorStartPending ? null : executor;
            finishWithoutExecutor = executor == null;
        }

        @Nullable Throwable publicationFailure = notifyPresentationTransition(cancellationTransition);
        if (finishWithoutExecutor) {
            try {
                finish(LaunchStatus.CANCELLED, null, null);
            } catch (Error finishFailure) {
                suppressOnto(finishFailure, publicationFailure);
                throw finishFailure;
            }
        } else if (executorToCancel != null) {
            try {
                executorToCancel.cancel();
            } catch (RuntimeException exception) {
                finish(LaunchStatus.FAILED, null, exception);
            } catch (Error error) {
                suppressOnto(error, publicationFailure);
                failAndRethrow(error);
            }
        }
        reportCleanupFailure(publicationFailure);
        return true;
    }

    /// Clears the startup-pending marker and returns the executor when deferred cancellation must now be forwarded.
    ///
    /// @param startedExecutor executor whose start invocation returned or failed
    /// @return executor to cancel, or null when cancellation is absent or preparation already terminated
    private @Nullable TaskExecutor markExecutorStartCompleted(TaskExecutor startedExecutor) {
        synchronized (stateLock) {
            if (executor != startedExecutor) {
                return null;
            }
            executorStartPending = false;
            return cancellationRequested && status == LaunchStatus.PREPARING ? startedExecutor : null;
        }
    }

    /// Republishes the current executor-adapter snapshot through the stable session presentation identity.
    ///
    /// The event payload is intentionally ignored because a concurrent adapter update may make it stale before this
    /// callback obtains the session lock. Terminal sessions and replaced adapters cannot modify session state.
    ///
    /// @param sourcePresentation adapter whose current snapshot should be reconciled
    private void presentationChanged(TaskExecutorPresentationModel sourcePresentation) {
        @Nullable PresentationTransition transition;
        synchronized (stateLock) {
            if (status != LaunchStatus.PREPARING || executorPresentation != sourcePresentation) {
                return;
            }
            TaskSnapshot replacement = sourcePresentation.snapshot();
            if (cancellationRequested) {
                replacement = withCancellationDisabled(replacement);
            }
            transition = replacePresentationSnapshotLocked(replacement);
        }
        reportCleanupFailure(notifyPresentationTransition(transition));
    }

    /// Creates a snapshot that cannot reopen cancellation after the session accepted a request.
    ///
    /// @param snapshot source presentation snapshot
    /// @return source snapshot or an equivalent snapshot with cancellation disabled
    private static TaskSnapshot withCancellationDisabled(TaskSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.cancelable()) {
            return snapshot;
        }
        return new TaskSnapshot(
                snapshot.title(),
                snapshot.phase(),
                snapshot.progress(),
                snapshot.status(),
                false,
                snapshot.details());
    }

    /// Commits one presentation replacement while holding the session lock.
    ///
    /// @param replacement immutable replacement snapshot
    /// @return exact transition to notify, or null when the state did not change
    private @Nullable PresentationTransition replacePresentationSnapshotLocked(TaskSnapshot replacement) {
        TaskSnapshot previous = presentationSnapshot;
        presentationSnapshot = Objects.requireNonNull(replacement, "replacement");
        return previous.equals(replacement) ? null : new PresentationTransition(previous, replacement);
    }

    /// Notifies one committed session presentation transition without changing state.
    ///
    /// @param transition committed transition, or null when no value changed
    /// @return observer failure retained for lock-free reporting, or null
    private @Nullable Throwable notifyPresentationTransition(@Nullable PresentationTransition transition) {
        if (transition == null) {
            return null;
        }
        return attempt(null, () -> presentationChanges.fireChange(
                transition.previous(),
                transition.current()));
    }

    /// Maps the executor's terminal event to a launch-session outcome.
    ///
    /// A non-null task result wins over a concurrent cancellation because the process has already crossed the
    /// ownership boundary and must remain observable rather than being silently abandoned.
    ///
    /// @param task completed root launch task
    /// @param taskExecutor executor that emitted the terminal event
    private void taskStopped(Task<ManagedProcess> task, TaskExecutor taskExecutor) {
        @Nullable ManagedProcess process = task.getResult();
        if (process != null) {
            finish(LaunchStatus.PROCESS_CREATED, process, null);
            return;
        }

        @Nullable Throwable executorFailure = taskExecutor.getFailure();
        @Nullable Exception taskFailure = task.getException();
        @Nullable Throwable resolvedFailure = executorFailure != null
                ? executorFailure
                : taskFailure;
        if (resolvedFailure instanceof CancellationException
                || taskExecutor.isCancelled() && resolvedFailure == null) {
            finish(LaunchStatus.CANCELLED, null, null);
            return;
        }

        if (resolvedFailure == null) {
            resolvedFailure = new IllegalStateException("Launch task completed without a managed process");
        }
        finish(LaunchStatus.FAILED, null, resolvedFailure);
    }

    /// Performs the one allowed terminal transition and releases all preparation ownership.
    ///
    /// The owning service slot is released before any listener publication or subscription cleanup, so failures in
    /// those external boundaries cannot strand the single-flight guard. Cleanup failures are combined using
    /// suppressed exceptions and rethrown only after all release steps have run.
    ///
    /// @param terminalStatus terminal status to publish
    /// @param process created process only for [LaunchStatus#PROCESS_CREATED]
    /// @param terminalFailure failure only for [LaunchStatus#FAILED]
    private void finish(
            LaunchStatus terminalStatus,
            @Nullable ManagedProcess process,
            @Nullable Throwable terminalFailure) {
        validateTerminalOutcome(terminalStatus, process, terminalFailure);

        @Nullable Subscription completionSubscription;
        @Nullable Subscription taskPresentationSubscription;
        @Nullable TaskExecutorPresentationModel taskPresentation;
        @Nullable PresentationTransition terminalPresentationTransition;
        synchronized (stateLock) {
            if (status != LaunchStatus.PREPARING) {
                return;
            }
            TaskSnapshot terminalPresentation = createTerminalPresentation(
                    terminalStatus,
                    terminalFailure,
                    presentationSnapshot);
            status = terminalStatus;
            createdProcess = process;
            failure = terminalFailure;
            terminalPresentationTransition = replacePresentationSnapshotLocked(terminalPresentation);
            executor = null;
            executorStartPending = false;
            completionSubscription = executorSubscription;
            executorSubscription = null;
            taskPresentationSubscription = presentationSubscription;
            presentationSubscription = null;
            taskPresentation = executorPresentation;
            executorPresentation = null;
        }

        @Nullable Throwable cleanupFailure = null;
        // Release single-flight ownership before invoking any observer or unsubscribe callback.
        cleanupFailure = attempt(cleanupFailure, () -> preparationFinished.accept(this));
        cleanupFailure = attempt(cleanupFailure, () -> statusProperty.setValue(terminalStatus));
        cleanupFailure = combineFailures(
                cleanupFailure,
                notifyPresentationTransition(terminalPresentationTransition));
        cleanupFailure = attempt(cleanupFailure, () -> unsubscribe(completionSubscription));
        cleanupFailure = attempt(cleanupFailure, () -> unsubscribe(taskPresentationSubscription));
        cleanupFailure = attempt(cleanupFailure, () -> closePresentation(taskPresentation));

        // CompletableFuture invokes synchronous dependents here; every public state surface is already terminal.
        if (process != null) {
            completion.complete(process);
        } else if (terminalFailure != null) {
            completion.completeExceptionally(terminalFailure);
        } else {
            completion.cancel(false);
        }
        reportCleanupFailure(cleanupFailure);
    }

    /// Creates task presentation matching the authoritative launch outcome.
    ///
    /// This matters when cancellation races process creation: the task executor may report cancellation while the
    /// launch contract must report the already-created process as success.
    ///
    /// @param terminalStatus authoritative launch terminal status
    /// @param terminalFailure non-cancellation failure, or null for other outcomes
    /// @param current current session presentation used to preserve phase and non-success progress
    /// @return immutable terminal task presentation
    private TaskSnapshot createTerminalPresentation(
            LaunchStatus terminalStatus,
            @Nullable Throwable terminalFailure,
            TaskSnapshot current) {
        TaskStatus taskStatus = switch (terminalStatus) {
            case PROCESS_CREATED -> TaskStatus.SUCCEEDED;
            case FAILED -> TaskStatus.FAILED;
            case CANCELLED -> TaskStatus.CANCELLED;
            case PREPARING -> throw new IllegalArgumentException("PREPARING is not terminal");
        };
        OptionalDouble progress = taskStatus == TaskStatus.SUCCEEDED
                ? OptionalDouble.of(1.0)
                : current.progress();
        String details = formatFailureDetails(terminalFailure);
        return new TaskSnapshot(
                presentationTitle,
                current.phase(),
                progress,
                taskStatus,
                false,
                details);
    }

    /// Formats optional failure diagnostics without allowing presentation work to block the terminal transition.
    ///
    /// A custom throwable can fail from [Throwable#printStackTrace(java.io.PrintWriter)], and an allocation failure
    /// can occur while building the trace. In either case the authoritative failure remains available through
    /// [#failure()] and [#completion()], while the optional presentation details fall back to an empty string.
    ///
    /// @param terminalFailure failure to format, or null for a non-failure terminal outcome
    /// @return stack trace text when available, otherwise an empty string
    private static String formatFailureDetails(@Nullable Throwable terminalFailure) {
        if (terminalFailure == null) {
            return "";
        }
        try {
            return StringUtils.getStackTrace(terminalFailure);
        } catch (RuntimeException | Error ignored) {
            return "";
        }
    }

    /// Runs one release step while retaining diagnostics and giving any [Error] precedence over runtime failures.
    ///
    /// @param previousFailure first previously observed cleanup failure, or null
    /// @param action release action to attempt
    /// @return combined failure, prioritizing [Error], or null when every attempted action succeeded
    private static @Nullable Throwable attempt(@Nullable Throwable previousFailure, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            return combineFailures(previousFailure, failure);
        }
        return previousFailure;
    }

    /// Combines unchecked failures without losing diagnostics or hiding an [Error] behind a runtime failure.
    ///
    /// @param previousFailure first earlier failure, or null
    /// @param laterFailure later failure, or null
    /// @return combined failure with [Error] precedence and secondary diagnostics suppressed
    private static @Nullable Throwable combineFailures(
            @Nullable Throwable previousFailure,
            @Nullable Throwable laterFailure) {
        if (previousFailure == null) {
            return laterFailure;
        }
        if (laterFailure == null || previousFailure == laterFailure) {
            return previousFailure;
        }
        if (laterFailure instanceof Error && !(previousFailure instanceof Error)) {
            laterFailure.addSuppressed(previousFailure);
            return laterFailure;
        }
        previousFailure.addSuppressed(laterFailure);
        return previousFailure;
    }

    /// Suppresses one secondary unchecked failure onto a primary error when both are distinct.
    ///
    /// @param primaryError primary error that will be rethrown
    /// @param secondaryFailure secondary failure, or null
    private static void suppressOnto(Error primaryError, @Nullable Throwable secondaryFailure) {
        if (secondaryFailure != null && primaryError != secondaryFailure) {
            primaryError.addSuppressed(secondaryFailure);
        }
    }

    /// Cancels one optional subscription.
    ///
    /// @param subscription subscription to cancel, or null when it was never installed
    private static void unsubscribe(@Nullable Subscription subscription) {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Closes one optional executor presentation adapter.
    ///
    /// @param presentation adapter to close, or null when it was never created
    private static void closePresentation(@Nullable TaskExecutorPresentationModel presentation) {
        if (presentation != null) {
            presentation.close();
        }
    }

    /// Reports an isolated runtime cleanup failure or rethrows an error after all release steps finish.
    ///
    /// @param failure retained failure, or null when cleanup completed normally
    private static void reportCleanupFailure(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            LOG.warning("A launch-session observer or cleanup callback failed", runtimeException);
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    /// Verifies that nullable terminal payloads match their explicit status.
    ///
    /// @param terminalStatus proposed terminal status
    /// @param process proposed process payload
    /// @param terminalFailure proposed failure payload
    private static void validateTerminalOutcome(
            LaunchStatus terminalStatus,
            @Nullable ManagedProcess process,
            @Nullable Throwable terminalFailure) {
        boolean valid = switch (terminalStatus) {
            case PROCESS_CREATED -> process != null && terminalFailure == null;
            case FAILED -> process == null && terminalFailure != null;
            case CANCELLED -> process == null && terminalFailure == null;
            case PREPARING -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("Invalid terminal launch outcome: " + terminalStatus);
        }
    }

    /// Owns the complete local resource set prepared before executor startup.
    ///
    /// @param executor stopped task executor
    /// @param presentation executor presentation adapter
    /// @param presentationSubscription bridge into the stable session presentation
    /// @param completionSubscription session completion listener registration
    @NotNullByDefault
    private record PreparedExecution(
            TaskExecutor executor,
            TaskExecutorPresentationModel presentation,
            Subscription presentationSubscription,
            Subscription completionSubscription) {
        /// Validates one complete prepared execution bundle.
        private PreparedExecution {
            Objects.requireNonNull(executor, "executor");
            Objects.requireNonNull(presentation, "presentation");
            Objects.requireNonNull(presentationSubscription, "presentationSubscription");
            Objects.requireNonNull(completionSubscription, "completionSubscription");
        }
    }

    /// Captures one exact committed session-presentation transition for lock-free notification.
    ///
    /// @param previous snapshot before the commit
    /// @param current committed replacement snapshot
    @NotNullByDefault
    private record PresentationTransition(TaskSnapshot previous, TaskSnapshot current) {
        /// Validates one immutable presentation transition.
        private PresentationTransition {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(current, "current");
        }
    }

    /// Bridges only the root executor's terminal event into this session.
    @NotNullByDefault
    private final class CompletionListener extends TaskListener {
        /// Root launch task whose result defines the process-creation boundary.
        private final Task<ManagedProcess> task;

        /// Creates a listener for the root task.
        ///
        /// @param task root launch task
        private CompletionListener(Task<ManagedProcess> task) {
            this.task = Objects.requireNonNull(task, "task");
        }

        /// Maps one executor terminal event to the corresponding session terminal state.
        ///
        /// @param success executor success flag; the task result remains the authoritative process signal
        /// @param taskExecutor executor emitting the event
        @Override
        public void onStop(boolean success, TaskExecutor taskExecutor) {
            taskStopped(task, taskExecutor);
        }
    }
}
