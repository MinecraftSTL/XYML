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
package space.minecraftstl.xyml.game.install;

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

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Runs one installation task and releases its service's single-flight slot at the first terminal state.
///
/// The completion listener and task-presentation adapter are installed before executor startup. Session state
/// remains stable while the underlying presentation adapter is replaced and closed during terminal cleanup.
@NotNullByDefault
final class DefaultGameInstallSession implements GameInstallSession {
    /// Serializes startup, cancellation, terminal selection, and owned executor references.
    private final Object stateLock = new Object();

    /// Stable request captured before asynchronous task preparation.
    private final GameInstallRequest request;

    /// Factory invoked exactly once unless cancellation wins before startup.
    private final GameInstallTaskFactory taskFactory;

    /// Explicit localized task title.
    private final String presentationTitle;

    /// Explicit localized phase shown before a core task becomes visible.
    private final String waitingPhase;

    /// Callback releasing this exact session from its owning service.
    private final Consumer<DefaultGameInstallSession> installationFinished;

    /// Toolkit-neutral observable installation lifecycle.
    private final SimpleObjectProperty<GameInstallStatus> statusProperty =
            new SimpleObjectProperty<>(this, "status", GameInstallStatus.PREPARING);

    /// Stable session-level task-presentation publisher.
    private final ValueChangeSupport<TaskSnapshot> presentationChanges = new ValueChangeSupport<>(this);

    /// Completion carrying normal completion, cancellation, or the exact terminal failure.
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    /// Latest authoritative installation status.
    private volatile GameInstallStatus status = GameInstallStatus.PREPARING;

    /// Latest immutable task-presentation snapshot.
    private volatile TaskSnapshot presentationSnapshot;

    /// Exact non-cancellation terminal failure, or null otherwise.
    private volatile @Nullable Throwable failure;

    /// Installed executor, or null before preparation and after terminal cleanup.
    private @Nullable TaskExecutor executor;

    /// Authoritative executor completion-listener registration.
    private @Nullable Subscription executorSubscription;

    /// Bridge from the executor presentation into this stable session identity.
    private @Nullable Subscription presentationSubscription;

    /// Executor presentation adapter, or null outside active execution.
    private @Nullable TaskExecutorPresentationModel executorPresentation;

    /// Whether asynchronous startup has claimed its only invocation.
    private boolean started;

    /// Whether [TaskExecutor#start()] is currently outside the session lock.
    private boolean executorStartPending;

    /// Whether cooperative cancellation has already been accepted.
    private boolean cancellationRequested;

    /// Creates a service-owned session that has not started task preparation.
    ///
    /// @param request stable installation request
    /// @param taskFactory request-specific task factory
    /// @param presentationTitle explicit localized task title
    /// @param waitingPhase explicit localized phase before task activation
    /// @param installationFinished callback releasing this session from the service slot
    DefaultGameInstallSession(
            GameInstallRequest request,
            GameInstallTaskFactory taskFactory,
            String presentationTitle,
            String waitingPhase,
            Consumer<DefaultGameInstallSession> installationFinished) {
        this.request = Objects.requireNonNull(request, "request");
        this.taskFactory = Objects.requireNonNull(taskFactory, "taskFactory");
        this.presentationTitle = Objects.requireNonNull(presentationTitle, "presentationTitle");
        this.waitingPhase = Objects.requireNonNull(waitingPhase, "waitingPhase");
        this.installationFinished = Objects.requireNonNull(installationFinished, "installationFinished");
        presentationSnapshot = new TaskSnapshot(
                presentationTitle,
                waitingPhase,
                OptionalDouble.empty(),
                TaskStatus.WAITING,
                false,
                "");
    }

    /// Creates, observes, and starts the installation task at most once.
    void start() {
        synchronized (stateLock) {
            if (started || status != GameInstallStatus.PREPARING) {
                return;
            }
            started = true;
        }

        final Task<?> task;
        try {
            task = Objects.requireNonNull(taskFactory.create(request), "install task factory returned null");
        } catch (RuntimeException taskCreationFailure) {
            finish(GameInstallStatus.FAILED, taskCreationFailure);
            return;
        } catch (Error error) {
            failAndRethrow(error);
            return;
        }

        synchronized (stateLock) {
            if (status != GameInstallStatus.PREPARING) {
                return;
            }
        }

        final PreparedExecution prepared;
        try {
            prepared = prepareExecution(task);
        } catch (RuntimeException preparationFailure) {
            finish(GameInstallStatus.FAILED, preparationFailure);
            return;
        } catch (Error error) {
            failAndRethrow(error);
            return;
        }

        boolean installed;
        synchronized (stateLock) {
            installed = status == GameInstallStatus.PREPARING;
            if (installed) {
                executor = prepared.executor();
                executorSubscription = prepared.completionSubscription();
                presentationSubscription = prepared.presentationSubscription();
                executorPresentation = prepared.presentation();
                executorStartPending = true;
            }
        }
        if (!installed) {
            reportCleanupFailure(releasePreparedExecution(prepared));
            return;
        }

        try {
            prepared.executor().start();
        } catch (RuntimeException startFailure) {
            markExecutorStartCompleted(prepared.executor());
            finish(GameInstallStatus.FAILED, startFailure);
            return;
        } catch (Error error) {
            markExecutorStartCompleted(prepared.executor());
            failAndRethrow(error);
            return;
        }

        @Nullable TaskExecutor executorToCancel = markExecutorStartCompleted(prepared.executor());
        if (executorToCancel != null) {
            try {
                executorToCancel.cancel();
            } catch (RuntimeException cancellationFailure) {
                finish(GameInstallStatus.FAILED, cancellationFailure);
            } catch (Error error) {
                failAndRethrow(error);
            }
        }
    }

    /// Fails a session whose preparation executor rejected startup.
    ///
    /// @param schedulingFailure exact scheduling failure
    void failBeforeStart(Throwable schedulingFailure) {
        finish(GameInstallStatus.FAILED, Objects.requireNonNull(schedulingFailure, "schedulingFailure"));
    }

    /// Creates every executor-owned listener before execution begins.
    ///
    /// @param task root installation task
    /// @return complete prepared execution bundle
    private PreparedExecution prepareExecution(Task<?> task) {
        TaskExecutor taskExecutor = task.executor();
        @Nullable Subscription completionSubscription = null;
        @Nullable TaskExecutorPresentationModel taskPresentation = null;
        @Nullable Subscription taskPresentationSubscription = null;
        try {
            completionSubscription = taskExecutor.subscribeTaskListener(new CompletionListener());
            TaskExecutorPresentationModel createdPresentation =
                    new TaskExecutorPresentationModel(taskExecutor, presentationTitle, waitingPhase);
            taskPresentation = createdPresentation;
            taskPresentationSubscription = createdPresentation.subscribe(
                    ignored -> presentationChanged(createdPresentation));
            return new PreparedExecution(
                    taskExecutor,
                    createdPresentation,
                    taskPresentationSubscription,
                    completionSubscription);
        } catch (RuntimeException | Error creationFailure) {
            @Nullable Throwable cleanupFailure = releaseDetachedExecution(
                    completionSubscription,
                    taskPresentationSubscription,
                    taskPresentation);
            suppressOnto(creationFailure, cleanupFailure);
            throw creationFailure;
        }
    }

    /// Returns the stable captured request.
    @Override
    public GameInstallRequest request() {
        return request;
    }

    /// Returns the latest authoritative lifecycle status.
    @Override
    public GameInstallStatus status() {
        return status;
    }

    /// Returns the observable lifecycle status.
    @Override
    public ReadOnlyProperty<GameInstallStatus> statusProperty() {
        return statusProperty;
    }

    /// Returns the latest stable task presentation.
    @Override
    public TaskSnapshot snapshot() {
        return presentationSnapshot;
    }

    /// Registers a listener against the stable session presentation identity.
    @Override
    public Subscription subscribe(ValueChangeListener<TaskSnapshot> listener) {
        return presentationChanges.subscribe(Objects.requireNonNull(listener, "listener"));
    }

    /// Requests cancellation through the session state machine.
    @Override
    public void requestCancellation() {
        cancel();
    }

    /// Returns a minimal completion view that cannot mutate the session future.
    @Override
    public CompletionStage<Void> completion() {
        return completion.minimalCompletionStage();
    }

    /// Returns the exact non-cancellation terminal failure.
    @Override
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    /// Accepts at most one cancellation while installation remains active.
    @Override
    public boolean cancel() {
        @Nullable TaskExecutor executorToCancel;
        boolean finishWithoutExecutor;
        @Nullable PresentationTransition cancellationTransition;
        synchronized (stateLock) {
            if (status != GameInstallStatus.PREPARING || cancellationRequested) {
                return false;
            }
            cancellationRequested = true;
            cancellationTransition = replacePresentationSnapshotLocked(
                    withCancellationDisabled(presentationSnapshot));
            executorToCancel = executorStartPending ? null : executor;
            finishWithoutExecutor = executor == null;
        }

        @Nullable Throwable publicationFailure = notifyPresentationTransition(cancellationTransition);
        try {
            if (finishWithoutExecutor) {
                finish(GameInstallStatus.CANCELLED, null);
            } else if (executorToCancel != null) {
                executorToCancel.cancel();
            }
        } catch (RuntimeException cancellationFailure) {
            finish(GameInstallStatus.FAILED, cancellationFailure);
        } catch (Error error) {
            suppressOnto(error, publicationFailure);
            failAndRethrow(error);
            return true;
        }
        reportCleanupFailure(publicationFailure);
        return true;
    }

    /// Clears the pending-start marker and returns an executor requiring deferred cancellation.
    ///
    /// @param startedExecutor executor whose start invocation returned
    /// @return executor to cancel, or null when cancellation is absent or already terminal
    private @Nullable TaskExecutor markExecutorStartCompleted(TaskExecutor startedExecutor) {
        synchronized (stateLock) {
            if (executor != startedExecutor) {
                return null;
            }
            executorStartPending = false;
            return cancellationRequested && status == GameInstallStatus.PREPARING
                    ? startedExecutor
                    : null;
        }
    }

    /// Republishes the current executor-adapter snapshot through this stable session.
    ///
    /// @param sourcePresentation adapter whose latest snapshot should be reconciled
    private void presentationChanged(TaskExecutorPresentationModel sourcePresentation) {
        @Nullable PresentationTransition transition;
        synchronized (stateLock) {
            if (status != GameInstallStatus.PREPARING || executorPresentation != sourcePresentation) {
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

    /// Maps an executor terminal event to an installation outcome.
    ///
    /// A successful terminal event wins over concurrent cancellation because repository state may already
    /// contain the installed instance. A real failure likewise remains visible instead of being masked.
    ///
    /// @param success executor success flag
    /// @param taskExecutor executor emitting the event
    private void taskStopped(boolean success, TaskExecutor taskExecutor) {
        @Nullable Throwable executorFailure = taskExecutor.getFailure();
        if (executorFailure instanceof CancellationException
                || executorFailure instanceof InterruptedException) {
            finish(GameInstallStatus.CANCELLED, null);
        } else if (executorFailure != null) {
            finish(GameInstallStatus.FAILED, executorFailure);
        } else if (success) {
            finish(GameInstallStatus.COMPLETED, null);
        } else if (taskExecutor.isCancelled() || cancellationRequested) {
            finish(GameInstallStatus.CANCELLED, null);
        } else {
            finish(GameInstallStatus.FAILED,
                    new IllegalStateException("Game installation stopped without a failure cause"));
        }
    }

    /// Performs the only allowed terminal transition and releases all execution ownership.
    ///
    /// @param terminalStatus completed, failed, or cancelled outcome
    /// @param terminalFailure exact failure only for [GameInstallStatus#FAILED]
    private void finish(
            GameInstallStatus terminalStatus,
            @Nullable Throwable terminalFailure) {
        validateTerminalOutcome(terminalStatus, terminalFailure);

        @Nullable Subscription completionSubscription;
        @Nullable Subscription taskPresentationSubscription;
        @Nullable TaskExecutorPresentationModel taskPresentation;
        @Nullable PresentationTransition terminalPresentationTransition;
        synchronized (stateLock) {
            if (status != GameInstallStatus.PREPARING) {
                return;
            }
            TaskSnapshot terminalPresentation = createTerminalPresentation(
                    terminalStatus,
                    terminalFailure,
                    presentationSnapshot);
            status = terminalStatus;
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
        cleanupFailure = attempt(cleanupFailure, () -> installationFinished.accept(this));
        cleanupFailure = attempt(cleanupFailure, () -> statusProperty.setValue(terminalStatus));
        cleanupFailure = combineFailures(
                cleanupFailure,
                notifyPresentationTransition(terminalPresentationTransition));
        cleanupFailure = attempt(cleanupFailure, () -> unsubscribe(completionSubscription));
        cleanupFailure = attempt(cleanupFailure, () -> unsubscribe(taskPresentationSubscription));
        cleanupFailure = attempt(cleanupFailure, () -> closePresentation(taskPresentation));

        if (terminalStatus == GameInstallStatus.COMPLETED) {
            completion.complete(null);
        } else if (terminalFailure != null) {
            completion.completeExceptionally(terminalFailure);
        } else {
            completion.cancel(false);
        }
        reportCleanupFailure(cleanupFailure);
    }

    /// Transitions to failure before rethrowing an Error unchanged.
    ///
    /// @param primaryError task preparation, startup, or cancellation error
    private void failAndRethrow(Error primaryError) {
        try {
            finish(GameInstallStatus.FAILED, primaryError);
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != primaryError) {
                primaryError.addSuppressed(cleanupFailure);
            }
        }
        throw primaryError;
    }

    /// Creates the terminal task presentation matching authoritative session state.
    ///
    /// @param terminalStatus authoritative session status
    /// @param terminalFailure failure for failed status, or null
    /// @param current latest active presentation
    /// @return terminal presentation
    private static TaskSnapshot createTerminalPresentation(
            GameInstallStatus terminalStatus,
            @Nullable Throwable terminalFailure,
            TaskSnapshot current) {
        TaskStatus taskStatus = switch (terminalStatus) {
            case COMPLETED -> TaskStatus.SUCCEEDED;
            case FAILED -> TaskStatus.FAILED;
            case CANCELLED -> TaskStatus.CANCELLED;
            case PREPARING -> throw new IllegalArgumentException("Preparing is not terminal");
        };
        OptionalDouble progress = terminalStatus == GameInstallStatus.COMPLETED
                ? OptionalDouble.of(1.0)
                : current.progress();
        String details = terminalFailure == null ? "" : safeStackTrace(terminalFailure);
        return new TaskSnapshot(
                current.title(),
                current.phase(),
                progress,
                taskStatus,
                false,
                details);
    }

    /// Formats failure details without allowing a hostile throwable to block terminal state.
    ///
    /// @param terminalFailure failure to format
    /// @return stack trace text, or an empty value when formatting itself fails
    private static String safeStackTrace(Throwable terminalFailure) {
        try {
            return StringUtils.getStackTrace(Objects.requireNonNull(terminalFailure, "terminalFailure"));
        } catch (RuntimeException | Error formattingFailure) {
            return "";
        }
    }

    /// Prevents presentation cancellation from being re-enabled after acceptance.
    ///
    /// @param snapshot source snapshot
    /// @return source or equivalent non-cancelable snapshot
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
    /// @param replacement immutable replacement
    /// @return exact transition, or null when unchanged
    private @Nullable PresentationTransition replacePresentationSnapshotLocked(TaskSnapshot replacement) {
        TaskSnapshot previous = presentationSnapshot;
        presentationSnapshot = Objects.requireNonNull(replacement, "replacement");
        return previous.equals(replacement) ? null : new PresentationTransition(previous, replacement);
    }

    /// Publishes one already-committed presentation transition.
    ///
    /// @param transition transition, or null when unchanged
    /// @return observer failure, or null
    private @Nullable Throwable notifyPresentationTransition(@Nullable PresentationTransition transition) {
        if (transition == null) {
            return null;
        }
        return attempt(null, () -> presentationChanges.fireChange(
                transition.previous(),
                transition.current()));
    }

    /// Releases a prepared execution that lost a terminal-state race.
    ///
    /// @param prepared detached execution
    /// @return accumulated cleanup failure, or null
    private static @Nullable Throwable releasePreparedExecution(PreparedExecution prepared) {
        return releaseDetachedExecution(
                prepared.completionSubscription(),
                prepared.presentationSubscription(),
                prepared.presentation());
    }

    /// Releases detached subscriptions and presentation in dependency order.
    ///
    /// @param completionSubscription completion listener, or null
    /// @param taskPresentationSubscription session presentation bridge, or null
    /// @param taskPresentation executor presentation, or null
    /// @return accumulated cleanup failure, or null
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

    /// Executes one cleanup action while retaining all failures.
    ///
    /// @param previousFailure prior failure, or null
    /// @param action cleanup or observer action
    /// @return accumulated failure, or null
    private static @Nullable Throwable attempt(
            @Nullable Throwable previousFailure,
            Runnable action) {
        try {
            Objects.requireNonNull(action, "action").run();
            return previousFailure;
        } catch (RuntimeException | Error actionFailure) {
            return combineFailures(previousFailure, actionFailure);
        }
    }

    /// Accumulates failures while preserving Error severity.
    ///
    /// @param previousFailure prior failure, or null
    /// @param laterFailure later failure, or null
    /// @return severity-preserving accumulated failure, or null
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

    /// Suppresses one secondary failure onto a primary throwable.
    ///
    /// @param primary primary throwable
    /// @param secondary secondary failure, or null
    private static void suppressOnto(Throwable primary, @Nullable Throwable secondary) {
        if (secondary != null && primary != secondary) {
            primary.addSuppressed(secondary);
        }
    }

    /// Cancels one optional subscription.
    ///
    /// @param subscription subscription, or null
    private static void unsubscribe(@Nullable Subscription subscription) {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Closes one optional executor presentation adapter.
    ///
    /// @param presentation presentation adapter, or null
    private static void closePresentation(@Nullable TaskExecutorPresentationModel presentation) {
        if (presentation != null) {
            presentation.close();
        }
    }

    /// Logs isolated runtime cleanup failures or rethrows Error after state is terminal.
    ///
    /// @param cleanupFailure accumulated failure, or null
    private static void reportCleanupFailure(@Nullable Throwable cleanupFailure) {
        if (cleanupFailure instanceof RuntimeException runtimeFailure) {
            LOG.warning("A game-install observer or cleanup callback failed", runtimeFailure);
        }
        if (cleanupFailure instanceof Error error) {
            throw error;
        }
    }

    /// Verifies that terminal failure payload matches explicit status.
    ///
    /// @param terminalStatus proposed terminal status
    /// @param terminalFailure proposed terminal failure, or null
    private static void validateTerminalOutcome(
            GameInstallStatus terminalStatus,
            @Nullable Throwable terminalFailure) {
        boolean valid = switch (Objects.requireNonNull(terminalStatus, "terminalStatus")) {
            case COMPLETED, CANCELLED -> terminalFailure == null;
            case FAILED -> terminalFailure != null;
            case PREPARING -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("Invalid terminal game-install outcome: " + terminalStatus);
        }
    }

    /// Owns all executor resources prepared before startup.
    ///
    /// @param executor stopped executor
    /// @param presentation executor presentation adapter
    /// @param presentationSubscription bridge into session presentation
    /// @param completionSubscription authoritative completion listener
    @NotNullByDefault
    private record PreparedExecution(
            TaskExecutor executor,
            TaskExecutorPresentationModel presentation,
            Subscription presentationSubscription,
            Subscription completionSubscription) {
        /// Validates the complete prepared bundle.
        private PreparedExecution {
            Objects.requireNonNull(executor, "executor");
            Objects.requireNonNull(presentation, "presentation");
            Objects.requireNonNull(presentationSubscription, "presentationSubscription");
            Objects.requireNonNull(completionSubscription, "completionSubscription");
        }
    }

    /// Captures one exact committed presentation transition.
    ///
    /// @param previous snapshot before commit
    /// @param current committed replacement
    @NotNullByDefault
    private record PresentationTransition(TaskSnapshot previous, TaskSnapshot current) {
        /// Validates transition snapshots.
        private PresentationTransition {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(current, "current");
        }
    }

    /// Bridges the root executor's authoritative terminal event into this session.
    @NotNullByDefault
    private final class CompletionListener extends TaskListener {
        /// Creates the stateless terminal listener.
        private CompletionListener() {
        }

        /// Maps one executor terminal event to installation state.
        ///
        /// @param success executor success flag
        /// @param taskExecutor executor emitting the event
        @Override
        public void onStop(boolean success, TaskExecutor taskExecutor) {
            taskStopped(success, taskExecutor);
        }
    }
}
