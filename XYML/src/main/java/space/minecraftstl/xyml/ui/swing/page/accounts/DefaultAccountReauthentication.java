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
import space.minecraftstl.xyml.auth.AuthInfo;
import space.minecraftstl.xyml.auth.AuthenticationException;
import space.minecraftstl.xyml.ui.UiDispatcher;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Toolkit-neutral credential-expiry recovery state machine.
///
/// One caller-owned executor performs lookup, prompts, network authentication, and persistence in sequence.
/// UI-only progress is dispatched separately. Classic authentication failures return to the password prompt;
/// OAuth authentication failures offer retry. Successful `AuthInfo` ownership transfers to the completion caller.
@NotNullByDefault
public final class DefaultAccountReauthentication implements AccountReauthentication {
    /// Authentication and persistence boundary.
    private final AccountReauthenticationGateway gateway;

    /// Blocking prompts and UI progress boundary.
    private final AccountReauthenticationInteraction interaction;

    /// Caller-owned executor for all blocking work.
    private final ExecutorService executor;

    /// UI dispatcher for nonblocking progress and terminal error presentation.
    private final UiDispatcher uiDispatcher;

    /// Active operation, or null while idle.
    private final AtomicReference<@Nullable Session> active = new AtomicReference<>();

    /// Whether this service permanently rejects new work.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates an injectable reauthentication service.
    ///
    /// This service never shuts down `executor`.
    ///
    /// @param gateway authentication and persistence boundary
    /// @param interaction prompt and progress boundary
    /// @param executor caller-owned blocking-work executor
    /// @param uiDispatcher UI progress dispatcher
    public DefaultAccountReauthentication(
            AccountReauthenticationGateway gateway,
            AccountReauthenticationInteraction interaction,
            ExecutorService executor,
            UiDispatcher uiDispatcher) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.interaction = Objects.requireNonNull(interaction, "interaction");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
    }

    /// Starts one operation and returns a cancellation-aware completion immediately.
    @Override
    public CompletionStage<AuthInfo> reauthenticate(String accountId) {
        Objects.requireNonNull(accountId, "accountId");
        if (closed.get()) {
            throw new IllegalStateException("Account reauthentication service is closed");
        }
        Session session = new Session();
        if (!active.compareAndSet(null, session)) {
            throw new IllegalStateException("Another account reauthentication is already active");
        }
        if (closed.get()) {
            active.compareAndSet(session, null);
            throw new IllegalStateException("Account reauthentication service is closed");
        }
        try {
            Future<?> worker = executor.submit(() -> runSession(session, accountId));
            session.attachWorker(worker);
            return session.completion;
        } catch (RuntimeException failure) {
            active.compareAndSet(session, null);
            throw failure;
        }
    }

    /// Cancels the active operation and permanently closes this service.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        @Nullable Session session = active.get();
        if (session != null) {
            session.cancel();
        } else {
            interaction.closeCurrentInteraction();
        }
    }

    /// Runs one complete recovery operation on the caller executor.
    ///
    /// @param session active session
    /// @param accountId stable account identifier
    private void runSession(Session session, String accountId) {
        @Nullable AccountReauthenticationTarget target = null;
        @Nullable PreparedReauthentication prepared = null;
        try {
            session.requireActive();
            target = gateway.describe(accountId);
            if (target.storageReadOnly() && !interaction.confirmReadOnlyStorage(target)) {
                throw new AccountReauthenticationCancelledException();
            }
            session.requireActive();
            publishProgress(session, target, AccountReauthenticationNotice.authenticating());
            prepared = switch (target.kind()) {
                case CLASSIC_PASSWORD -> authenticateClassic(session, target);
                case OAUTH_DEVICE_CODE -> authenticateOAuth(session, target);
                case DIRECT -> gateway.authenticateDirect(target);
            };
            session.requireActive();
            publishProgress(session, target, AccountReauthenticationNotice.persisting());
            gateway.persist(prepared, target.storageReadOnly());
            session.requireActive();
            completeSuccessfully(session, prepared.authInfo());
            prepared = null;
        } catch (AccountReauthenticationCancelledException | CancellationException failure) {
            completeCancelled(session);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            completeCancelled(session);
        } catch (Throwable failure) {
            if (session.cancellationRequested.get()) {
                completeCancelled(session);
            } else {
                completeFailed(session, target, accountId, failure);
            }
        } finally {
            if (prepared != null) {
                closeAuthInfo(prepared.authInfo());
            }
            interaction.closeCurrentInteraction();
            active.compareAndSet(session, null);
        }
    }

    /// Repeats a native password prompt until classic authentication succeeds or is cancelled.
    ///
    /// @param session active session
    /// @param target classic target
    /// @return successful prepared result
    /// @throws Exception when a non-authentication failure occurs
    private PreparedReauthentication authenticateClassic(
            Session session,
            AccountReauthenticationTarget target) throws Exception {
        @Nullable String localizedError = null;
        while (true) {
            session.requireActive();
            char[] passwordCharacters = interaction.requestPassword(target, localizedError);
            try {
                String password = new String(passwordCharacters);
                session.requireActive();
                return gateway.authenticateClassic(target, password);
            } catch (AuthenticationException failure) {
                session.requireActive();
                localizedError = gateway.localizeFailure(failure);
            } finally {
                Arrays.fill(passwordCharacters, '\0');
            }
        }
    }

    /// Repeats existing OAuth expiry recovery while the user chooses retry.
    ///
    /// @param session active session
    /// @param target OAuth target
    /// @return successful prepared result
    /// @throws Exception when a non-authentication failure occurs
    private PreparedReauthentication authenticateOAuth(
            Session session,
            AccountReauthenticationTarget target) throws Exception {
        while (true) {
            session.requireActive();
            try {
                return gateway.authenticateOAuth(
                        target,
                        notice -> publishProgress(session, target, notice));
            } catch (AuthenticationException failure) {
                session.requireActive();
                String localized = gateway.localizeFailure(failure);
                interaction.closeCurrentInteraction();
                if (!interaction.confirmOAuthRetry(target, localized)) {
                    throw new AccountReauthenticationCancelledException();
                }
                publishProgress(session, target, AccountReauthenticationNotice.authenticating());
            }
        }
    }

    /// Dispatches progress while this session remains active.
    ///
    /// @param session active session
    /// @param target target metadata
    /// @param notice progress notice
    private void publishProgress(
            Session session,
            AccountReauthenticationTarget target,
            AccountReauthenticationNotice notice) {
        Objects.requireNonNull(notice, "notice");
        if (session.cancellationRequested.get()) {
            return;
        }
        uiDispatcher.dispatch(() -> {
            if (!session.cancellationRequested.get() && !session.completion.isDone()) {
                interaction.onProgress(target, notice);
            }
        });
    }

    /// Transfers successful AuthInfo ownership to the completion caller.
    ///
    /// @param session active session
    /// @param authInfo successful launch authentication data
    private void completeSuccessfully(Session session, AuthInfo authInfo) {
        if (!session.completion.complete(authInfo)) {
            closeAuthInfo(authInfo);
        }
    }

    /// Completes one session with standard cancellation.
    ///
    /// @param session active session
    private void completeCancelled(Session session) {
        session.cancellationRequested.set(true);
        session.completion.cancel(false);
    }

    /// Localizes, presents, and completes one terminal failure.
    ///
    /// @param session active session
    /// @param target resolved target, or null if lookup failed
    /// @param accountId stable target ID used if target lookup failed
    /// @param failure original failure
    private void completeFailed(
            Session session,
            @Nullable AccountReauthenticationTarget target,
            String accountId,
            Throwable failure) {
        String localized = gateway.localizeFailure(failure);
        AccountReauthenticationTarget presentationTarget = target == null
                ? new AccountReauthenticationTarget(
                        accountId,
                        accountId,
                        AccountReauthenticationKind.DIRECT,
                        false,
                        false)
                : target;
        uiDispatcher.dispatch(() -> interaction.showFailure(presentationTarget, localized));
        session.completion.completeExceptionally(
                new AccountReauthenticationException(localized, failure));
    }

    /// Closes authentication data that cannot be transferred to a caller.
    ///
    /// @param authInfo abandoned authentication data
    private static void closeAuthInfo(AuthInfo authInfo) {
        try {
            authInfo.close();
        } catch (Exception failure) {
            LOG.warning("Failed to close abandoned account authentication data", failure);
        }
    }

    /// Mutable lifecycle for one operation.
    @NotNullByDefault
    private final class Session {
        /// Public cancellation-aware completion.
        private final CompletableFuture<AuthInfo> completion = new CompletableFuture<>();

        /// Whether any cancellation path has won.
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();

        /// Submitted worker, or null during submission.
        private final AtomicReference<@Nullable Future<?>> worker = new AtomicReference<>();

        /// Creates a session and observes caller cancellation of its returned future.
        private Session() {
            completion.whenComplete((result, failure) -> {
                if (completion.isCancelled()) {
                    cancelResources();
                }
            });
        }

        /// Attaches the submitted worker and honors earlier cancellation.
        ///
        /// @param submittedWorker submitted worker
        private void attachWorker(Future<?> submittedWorker) {
            if (!worker.compareAndSet(null, submittedWorker)) {
                throw new IllegalStateException("Reauthentication worker was already attached");
            }
            if (cancellationRequested.get()) {
                submittedWorker.cancel(true);
            }
        }

        /// Cancels the public completion and all owned resources once.
        private void cancel() {
            if (cancellationRequested.compareAndSet(false, true)) {
                completion.cancel(false);
            } else {
                cancelResources();
            }
        }

        /// Interrupts the worker and closes current UI without blocking.
        private void cancelResources() {
            cancellationRequested.set(true);
            try {
                gateway.cancelActiveAuthentication();
            } catch (RuntimeException failure) {
                LOG.warning("Failed to release active account authentication callbacks", failure);
            }
            @Nullable Future<?> submittedWorker = worker.get();
            if (submittedWorker != null) {
                submittedWorker.cancel(true);
            }
            interaction.closeCurrentInteraction();
        }

        /// Rejects work after cancellation and preserves interrupt status.
        private void requireActive() throws InterruptedException {
            if (cancellationRequested.get()) {
                throw new CancellationException("Account reauthentication was cancelled");
            }
            if (Thread.interrupted()) {
                throw new InterruptedException("Account reauthentication worker was interrupted");
            }
        }
    }
}
