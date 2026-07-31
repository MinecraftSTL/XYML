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
package space.minecraftstl.xyml.ui.swing.runtime;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.launch.LaunchRequest;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Owns one cancellable local launch-script export while preserving the launcher launch preparation chain.
///
/// Task construction runs on the caller-owned preparation executor because launcher metadata normalization may block.
/// The Core task graph then owns its own asynchronous work. At most one export is active, and close prevents a task
/// from starting after shutdown while cancelling one already-started task executor.
@NotNullByDefault
public final class LaunchScriptExportService implements AutoCloseable {
    /// Builder resolving one immutable launch request into an unstarted export task.
    private final BiFunction<LaunchRequest, Path, Task<Path>> taskBuilder;

    /// Caller-owned executor used only for potentially blocking task construction.
    private final Executor preparationExecutor;

    /// Active export reservation from scheduling through terminal task completion, or null while idle.
    private final AtomicReference<@Nullable ExportOperation> activeOperation = new AtomicReference<>();

    /// Prevents later scheduling after the command-owner lifecycle closes.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates the production service around the launcher stable-ID task factory.
    ///
    /// @param taskFactory factory resolving a captured selection into the ordinary launch preparation chain
    /// @param preparationExecutor caller-owned executor for synchronous task construction
    public LaunchScriptExportService(
            LauncherLaunchTaskFactory taskFactory,
            Executor preparationExecutor) {
        this(
                Objects.requireNonNull(taskFactory, "taskFactory")::createLaunchScriptTask,
                preparationExecutor);
    }

    /// Creates the service around explicit collaborators for focused lifecycle tests.
    ///
    /// @param taskBuilder builder creating an unstarted export task away from the Swing EDT
    /// @param preparationExecutor caller-owned executor for synchronous task construction
    LaunchScriptExportService(
            BiFunction<LaunchRequest, Path, Task<Path>> taskBuilder,
            Executor preparationExecutor) {
        this.taskBuilder = Objects.requireNonNull(taskBuilder, "taskBuilder");
        this.preparationExecutor = Objects.requireNonNull(preparationExecutor, "preparationExecutor");
    }

    /// Schedules one local launch-script export for an immutable selected account and instance.
    ///
    /// @param request stable account, game-directory, and instance identifiers captured by the home model
    /// @param scriptFile selected local script destination
    /// @return terminal stage yielding the exact written script path
    public CompletionStage<Path> export(LaunchRequest request, Path scriptFile) {
        LaunchRequest capturedRequest = Objects.requireNonNull(request, "request");
        Path destination = Objects.requireNonNull(scriptFile, "scriptFile").toAbsolutePath().normalize();
        if (closed.get()) {
            return failedStage(new IllegalStateException("Launch-script export service is closed"));
        }

        ExportOperation operation = new ExportOperation(capturedRequest, destination);
        if (!activeOperation.compareAndSet(null, operation)) {
            return failedStage(new IllegalStateException("A launch-script export is already running"));
        }
        if (closed.get()) {
            activeOperation.compareAndSet(operation, null);
            cancelOperation(operation, "Launch-script export service is closed");
            return operation.completion();
        }

        try {
            preparationExecutor.execute(() -> createAndStart(operation));
        } catch (RuntimeException schedulingFailure) {
            finishFailure(operation, schedulingFailure);
        } catch (Error schedulingFailure) {
            finishFailure(operation, schedulingFailure);
            throw schedulingFailure;
        }
        return operation.completion();
    }

    /// Cancels the active export and permanently rejects later commands without closing caller-owned executors.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        @Nullable ExportOperation operation = activeOperation.getAndSet(null);
        if (operation != null) {
            cancelOperation(operation, "Launch-script export service is closed");
        }
    }

    /// Builds the task outside the EDT, registers completion first, and then starts it only while still owned.
    ///
    /// @param operation exact active export reservation
    private void createAndStart(ExportOperation operation) {
        if (!isActive(operation)) {
            return;
        }

        final Task<Path> task;
        try {
            task = Objects.requireNonNull(
                    taskBuilder.apply(operation.request(), operation.scriptFile()),
                    "taskBuilder returned null");
        } catch (RuntimeException | Error creationFailure) {
            finishFailure(operation, creationFailure);
            return;
        }
        if (!isActive(operation)) {
            return;
        }

        final TaskExecutor executor;
        try {
            executor = task.executor();
        } catch (RuntimeException | Error executorFailure) {
            finishFailure(operation, executorFailure);
            return;
        }
        if (!operation.installExecutor(executor)) {
            return;
        }

        final Subscription completionSubscription;
        try {
            completionSubscription = executor.subscribeTaskListener(new ExportCompletionListener(operation, task));
        } catch (RuntimeException | Error listenerFailure) {
            finishFailure(operation, listenerFailure);
            return;
        }
        if (!operation.installCompletionSubscription(completionSubscription)) {
            completionSubscription.unsubscribe();
            return;
        }

        try {
            if (!operation.start(executor)) {
                return;
            }
        } catch (RuntimeException | Error startFailure) {
            finishFailure(operation, startFailure);
        }
    }

    /// Returns whether this exact reservation still belongs to the open service.
    ///
    /// @param operation candidate export reservation
    /// @return whether creation may continue
    private boolean isActive(ExportOperation operation) {
        return !closed.get() && activeOperation.get() == operation && !operation.isCancellationRequested();
    }

    /// Completes one active export successfully, after checking the task result is present.
    ///
    /// @param operation exact export reservation
    /// @param task completed source task
    private void finishSuccess(ExportOperation operation, Task<Path> task) {
        if (!activeOperation.compareAndSet(operation, null)) {
            return;
        }
        @Nullable Path result = task.getResult();
        operation.releaseCompletionSubscription();
        if (result == null) {
            operation.completeExceptionally(new IllegalStateException(
                    "Launch-script export completed without a script path"));
            return;
        }
        operation.complete(result);
    }

    /// Completes one active export exceptionally and releases its listener registration.
    ///
    /// @param operation exact export reservation
    /// @param failure terminal preparation or execution failure
    private void finishFailure(ExportOperation operation, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (!activeOperation.compareAndSet(operation, null)) {
            return;
        }
        operation.releaseCompletionSubscription();
        operation.completeExceptionally(failure);
    }

    /// Cancels one active reservation before or after executor startup without starting new work during shutdown.
    ///
    /// @param operation export reservation to cancel
    /// @param reason stable cancellation reason
    private void cancelOperation(ExportOperation operation, String reason) {
        ExportOperation target = Objects.requireNonNull(operation, "operation");
        @Nullable TaskExecutor executor = target.requestCancellation();
        target.releaseCompletionSubscription();
        target.completeExceptionally(new CancellationException(Objects.requireNonNull(reason, "reason")));
        if (executor != null) {
            try {
                executor.cancel();
            } catch (RuntimeException cancellationFailure) {
                LOG.warning("Failed to cancel launch-script export", cancellationFailure);
            }
        }
    }

    /// Creates a failed terminal stage without throwing from an asynchronous command boundary.
    ///
    /// @param failure terminal command rejection
    /// @return failed stage preserving the exact failure
    private static CompletionStage<Path> failedStage(Throwable failure) {
        return CompletableFuture.<Path>failedFuture(Objects.requireNonNull(failure, "failure"))
                .minimalCompletionStage();
    }

    /// Receives one task executor terminal event for the exact active export task.
    @NotNullByDefault
    private final class ExportCompletionListener extends TaskListener {
        /// Export reservation that owns this listener.
        private final ExportOperation operation;

        /// Exact task whose result is exposed after successful execution.
        private final Task<Path> task;

        /// Creates a listener tied to one task and one active reservation.
        ///
        /// @param operation export reservation owning the listener
        /// @param task task yielding the generated script path
        private ExportCompletionListener(ExportOperation operation, Task<Path> task) {
            this.operation = Objects.requireNonNull(operation, "operation");
            this.task = Objects.requireNonNull(task, "task");
        }

        /// Completes the active command from the authoritative executor terminal outcome.
        ///
        /// @param success whether the complete task graph succeeded
        /// @param executor stopped source executor
        @Override
        public void onStop(boolean success, TaskExecutor executor) {
            TaskExecutor stoppedExecutor = Objects.requireNonNull(executor, "executor");
            if (success) {
                finishSuccess(operation, task);
                return;
            }
            @Nullable Throwable failure = stoppedExecutor.getFailure();
            if (failure != null) {
                finishFailure(operation, failure);
            } else if (stoppedExecutor.isCancelled()) {
                finishFailure(operation, new CancellationException("Launch-script export was cancelled"));
            } else {
                finishFailure(operation, new IllegalStateException(
                        "Launch-script export stopped without a failure"));
            }
        }
    }

    /// Coordinates startup, terminal ownership, subscription cleanup, and shutdown cancellation for one export.
    @NotNullByDefault
    private static final class ExportOperation {
        /// Stable account and instance IDs captured before file selection work completes.
        private final LaunchRequest request;

        /// Normalized local script destination.
        private final Path scriptFile;

        /// Result completed exactly once by task completion or lifecycle shutdown.
        private final CompletableFuture<Path> completion = new CompletableFuture<>();

        /// Created task executor, or null before task preparation reaches executor allocation.
        private @Nullable TaskExecutor executor;

        /// Registered terminal listener subscription, or null before listener installation or after release.
        private @Nullable Subscription completionSubscription;

        /// Whether service close requested cancellation before or after executor startup.
        private boolean cancellationRequested;

        /// Whether [TaskExecutor#start()] has returned, making cancellation safe to invoke.
        private boolean executorStarted;

        /// Creates one reservation before preparation work is scheduled.
        ///
        /// @param request immutable stable launch identifiers
        /// @param scriptFile normalized local script destination
        private ExportOperation(LaunchRequest request, Path scriptFile) {
            this.request = Objects.requireNonNull(request, "request");
            this.scriptFile = Objects.requireNonNull(scriptFile, "scriptFile");
        }

        /// Returns the captured stable launch request.
        ///
        /// @return immutable account and instance identifiers
        private LaunchRequest request() {
            return request;
        }

        /// Returns the normalized local script target.
        ///
        /// @return local script destination
        private Path scriptFile() {
            return scriptFile;
        }

        /// Returns an isolated completion-stage view for the UI command boundary.
        ///
        /// A manual relay preserves a direct `CancellationException`; the JDK minimal-stage relay
        /// wraps cancellation in `CompletionException` when callers request a completable future.
        /// External completion of the returned future cannot mutate the operation-owned future.
        ///
        /// @return terminal script result stage
        private CompletionStage<Path> completion() {
            CompletableFuture<Path> exposedCompletion = new CompletableFuture<>();
            completion.whenComplete((@Nullable Path result, @Nullable Throwable failure) -> {
                if (failure != null) {
                    exposedCompletion.completeExceptionally(failure);
                } else {
                    exposedCompletion.complete(Objects.requireNonNull(
                            result,
                            "Successful launch-script export completed without a path"));
                }
            });
            return exposedCompletion;
        }

        /// Installs an executor only while close has not cancelled this reservation.
        ///
        /// @param candidate created stopped executor
        /// @return whether startup may continue
        private synchronized boolean installExecutor(TaskExecutor candidate) {
            if (cancellationRequested || completion.isDone()) {
                return false;
            }
            executor = Objects.requireNonNull(candidate, "candidate");
            return true;
        }

        /// Installs a terminal-listener registration only while the reservation remains live.
        ///
        /// @param candidate completion listener registration
        /// @return whether the caller retains ownership of the registration
        private synchronized boolean installCompletionSubscription(Subscription candidate) {
            if (cancellationRequested || completion.isDone()) {
                return false;
            }
            completionSubscription = Objects.requireNonNull(candidate, "candidate");
            return true;
        }

        /// Starts the stopped executor while holding the operation lock against cancellation-before-start races.
        ///
        /// @param candidate exact stopped executor previously installed for this reservation
        /// @return whether the executor started
        private synchronized boolean start(TaskExecutor candidate) {
            TaskExecutor source = Objects.requireNonNull(candidate, "candidate");
            if (cancellationRequested || completion.isDone() || executor != source) {
                return false;
            }
            source.start();
            executorStarted = true;
            return true;
        }

        /// Records cancellation and returns an executor only after it is safe to cancel.
        ///
        /// @return started executor to cancel, or null when startup has not begun or work is already terminal
        private synchronized @Nullable TaskExecutor requestCancellation() {
            cancellationRequested = true;
            return executorStarted ? executor : null;
        }

        /// Returns whether a close request has cancelled this reservation.
        ///
        /// @return `true` after cancellation was requested
        private synchronized boolean isCancellationRequested() {
            return cancellationRequested;
        }

        /// Removes the terminal listener registration exactly once.
        private void releaseCompletionSubscription() {
            @Nullable Subscription subscription;
            synchronized (this) {
                subscription = completionSubscription;
                completionSubscription = null;
            }
            if (subscription != null) {
                subscription.unsubscribe();
            }
        }

        /// Completes the UI-facing stage successfully.
        ///
        /// @param result generated local script path
        private void complete(Path result) {
            completion.complete(Objects.requireNonNull(result, "result"));
        }

        /// Completes the UI-facing stage with the exact terminal failure.
        ///
        /// @param failure terminal error or cancellation
        private void completeExceptionally(Throwable failure) {
            completion.completeExceptionally(Objects.requireNonNull(failure, "failure"));
        }
    }
}
