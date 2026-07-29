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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.UiDispatcher;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/// Runs one native account workflow at a time on a caller-owned executor.
///
/// Network authentication, backup, and launcher-state commits never run on the Swing EDT. Every listener
/// callback is dispatched through the injected UI dispatcher, and cancellation interrupts the worker
/// while also closing any native prompt owned by the interaction boundary.
@NotNullByDefault
public final class SwingAccountCreationCoordinator implements AutoCloseable {
    /// Vanilla-compatible offline-name characters.
    private static final Pattern OFFLINE_USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");

    /// Authentication and persistence boundary.
    private final AccountCreationGateway gateway;

    /// Blocking prompt and role-selection boundary.
    private final AccountCreationInteraction interaction;

    /// Caller-owned executor used for all potentially blocking work.
    private final ExecutorService executor;

    /// UI dispatcher used for listener delivery only.
    private final UiDispatcher uiDispatcher;

    /// Whether the environment explicitly disables invalid offline-name confirmation.
    private final boolean skipOfflineUsernameCheck;

    /// Current operation, or null while idle.
    private final AtomicReference<@Nullable Operation> currentOperation = new AtomicReference<>();

    /// Whether the coordinator has permanently closed.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates a coordinator using caller-owned execution and prompt boundaries.
    ///
    /// The coordinator never shuts down `executor`.
    ///
    /// @param gateway authentication and persistence boundary
    /// @param interaction confirmation and role-selection boundary
    /// @param executor caller-owned blocking-work executor
    /// @param uiDispatcher listener dispatcher
    /// @param skipOfflineUsernameCheck whether invalid offline names bypass confirmation
    public SwingAccountCreationCoordinator(
            AccountCreationGateway gateway,
            AccountCreationInteraction interaction,
            ExecutorService executor,
            UiDispatcher uiDispatcher,
            boolean skipOfflineUsernameCheck) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.interaction = Objects.requireNonNull(interaction, "interaction");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
        this.skipOfflineUsernameCheck = skipOfflineUsernameCheck;
    }

    /// Starts one request and rejects concurrent or post-close starts.
    ///
    /// @param request validated account request
    /// @param listener UI listener
    /// @return cancellable operation handle
    public AccountCreationOperation start(
            AccountCreationRequest request,
            AccountCreationListener listener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        if (closed.get()) {
            throw new IllegalStateException("Account creation coordinator is closed");
        }

        Operation operation = new Operation(listener);
        if (!currentOperation.compareAndSet(null, operation)) {
            throw new IllegalStateException("Another account creation operation is already running");
        }
        if (closed.get()) {
            currentOperation.compareAndSet(operation, null);
            throw new IllegalStateException("Account creation coordinator is closed");
        }

        try {
            Future<?> worker = executor.submit(() -> runOperation(operation, request));
            operation.attachWorker(worker);
            return operation;
        } catch (RuntimeException failure) {
            currentOperation.compareAndSet(operation, null);
            throw failure;
        }
    }

    /// Returns whether a name falls outside vanilla's one-to-sixteen-character limits.
    ///
    /// @param username offline profile name
    /// @return true when explicit confirmation is required
    public static boolean isInvalidOfflineUsername(String username) {
        Objects.requireNonNull(username, "username");
        return username.length() > 16 || !OFFLINE_USERNAME_PATTERN.matcher(username).matches();
    }

    /// Cancels any active operation and permanently rejects new work.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        @Nullable Operation operation = currentOperation.get();
        if (operation != null) {
            operation.cancel();
        }
    }

    /// Executes confirmation, authentication, backup, and commit on the caller executor.
    ///
    /// @param operation current operation
    /// @param request validated request
    private void runOperation(Operation operation, AccountCreationRequest request) {
        try {
            operation.requireActive();
            if (requiresOfflineUsernameConfirmation(request)
                    && !interaction.confirmInvalidOfflineUsername(
                            Objects.requireNonNull(request.username(), "offline username"))) {
                throw new AccountCreationCancelledException();
            }

            operation.requireActive();
            publishProgress(operation, AccountCreationNotice.authenticating());
            PreparedAccount prepared = gateway.authenticate(
                    request,
                    roles -> interaction.selectRole(roles),
                    notice -> publishProgress(operation, notice));

            operation.requireActive();
            if (gateway.isTargetReadOnly(prepared)) {
                if (!interaction.confirmReadOnlyStorage(prepared.portable())) {
                    throw new AccountCreationCancelledException();
                }
                operation.requireActive();
                publishProgress(operation, AccountCreationNotice.writingStorage());
                gateway.forceOverwriteTarget(prepared);
            }

            operation.requireActive();
            publishProgress(operation, AccountCreationNotice.writingStorage());
            gateway.commitAndSelect(prepared);
            AccountCreationResult result = new AccountCreationResult(
                    prepared.accountId(),
                    prepared.displayName(),
                    prepared.method(),
                    prepared.portable());
            completeSuccessfully(operation, result);
        } catch (AccountCreationCancelledException failure) {
            completeCancelled(operation, failure);
        } catch (CancellationException failure) {
            completeCancelled(operation, failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            completeCancelled(operation, new CancellationException("Account creation was interrupted"));
        } catch (Throwable failure) {
            if (operation.isCancellationRequested()) {
                completeCancelled(operation, new CancellationException("Account creation was cancelled"));
            } else {
                completeFailed(operation, failure);
            }
        }
    }

    /// Returns whether this request requires explicit invalid-name confirmation.
    ///
    /// @param request account request
    /// @return true when confirmation is required
    private boolean requiresOfflineUsernameConfirmation(AccountCreationRequest request) {
        return !skipOfflineUsernameCheck
                && request.method() == AccountCreationMethod.OFFLINE
                && isInvalidOfflineUsername(Objects.requireNonNull(request.username(), "offline username"));
    }

    /// Publishes one progress notice unless cancellation has already won.
    ///
    /// @param operation current operation
    /// @param notice progress notice
    private void publishProgress(Operation operation, AccountCreationNotice notice) {
        Objects.requireNonNull(notice, "notice");
        if (!operation.isCancellationRequested()) {
            uiDispatcher.dispatch(() -> {
                if (!operation.isCancellationRequested() && !operation.completion.isDone()) {
                    operation.listener.onProgress(notice);
                }
            });
        }
    }

    /// Completes one successful operation and delivers its result once.
    ///
    /// @param operation current operation
    /// @param result successful result
    private void completeSuccessfully(Operation operation, AccountCreationResult result) {
        if (!operation.completion.complete(result)) {
            return;
        }
        currentOperation.compareAndSet(operation, null);
        uiDispatcher.dispatch(() -> operation.listener.onSucceeded(result));
    }

    /// Completes one cancelled operation and delivers cancellation once.
    ///
    /// @param operation current operation
    /// @param failure cancellation reason
    private void completeCancelled(Operation operation, CancellationException failure) {
        if (!operation.completion.completeExceptionally(failure)) {
            return;
        }
        currentOperation.compareAndSet(operation, null);
        uiDispatcher.dispatch(operation.listener::onCancelled);
    }

    /// Converts a checked prompt cancellation into the public cancellation completion.
    ///
    /// @param operation current operation
    /// @param failure prompt cancellation
    private void completeCancelled(Operation operation, AccountCreationCancelledException failure) {
        CancellationException cancellation = new CancellationException(failure.getMessage());
        cancellation.initCause(failure);
        completeCancelled(operation, cancellation);
    }

    /// Localizes and completes one failed operation.
    ///
    /// @param operation current operation
    /// @param failure original failure
    private void completeFailed(Operation operation, Throwable failure) {
        if (!operation.completion.completeExceptionally(failure)) {
            return;
        }
        currentOperation.compareAndSet(operation, null);
        String localizedMessage;
        try {
            localizedMessage = gateway.localizeFailure(failure);
        } catch (RuntimeException localizationFailure) {
            failure.addSuppressed(localizationFailure);
            @Nullable String message = failure.getLocalizedMessage();
            localizedMessage = message == null || message.isBlank()
                    ? failure.getClass().getSimpleName()
                    : message;
        }
        String finalMessage = localizedMessage;
        uiDispatcher.dispatch(() -> operation.listener.onFailed(finalMessage, failure));
    }

    /// Mutable lifecycle owned by one start call.
    @NotNullByDefault
    private final class Operation implements AccountCreationOperation {
        /// UI listener for this operation.
        private final AccountCreationListener listener;

        /// Public terminal completion.
        private final CompletableFuture<AccountCreationResult> completion = new CompletableFuture<>();

        /// Whether cancellation has been requested.
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();

        /// Submitted worker, or null during the narrow submission race.
        private final AtomicReference<@Nullable Future<?>> worker = new AtomicReference<>();

        /// Creates one unsubmitted operation.
        ///
        /// @param listener UI listener
        private Operation(AccountCreationListener listener) {
            this.listener = listener;
        }

        /// Attaches the submitted worker and applies an earlier cancellation request.
        ///
        /// @param submittedWorker submitted worker
        private void attachWorker(Future<?> submittedWorker) {
            if (!worker.compareAndSet(null, submittedWorker)) {
                throw new IllegalStateException("Account creation worker was already attached");
            }
            if (cancellationRequested.get()) {
                submittedWorker.cancel(true);
            }
        }

        /// Requests cancellation and immediately publishes a terminal cancellation when needed.
        @Override
        public boolean cancel() {
            if (completion.isDone() || !cancellationRequested.compareAndSet(false, true)) {
                return false;
            }
            interaction.cancelPendingInteraction();
            @Nullable Future<?> submittedWorker = worker.get();
            if (submittedWorker != null) {
                submittedWorker.cancel(true);
            }
            completeCancelled(this, new CancellationException("Account creation was cancelled"));
            return true;
        }

        /// Returns the public terminal completion.
        @Override
        public java.util.concurrent.CompletionStage<AccountCreationResult> completion() {
            return completion;
        }

        /// Throws when cancellation has won before the next blocking step.
        private void requireActive() throws InterruptedException {
            if (cancellationRequested.get()) {
                throw new CancellationException("Account creation was cancelled");
            }
            if (Thread.interrupted()) {
                throw new InterruptedException("Account creation worker was interrupted");
            }
        }

        /// Returns whether cancellation has been requested.
        ///
        /// @return true after explicit cancellation
        private boolean isCancellationRequested() {
            return cancellationRequested.get();
        }
    }
}
