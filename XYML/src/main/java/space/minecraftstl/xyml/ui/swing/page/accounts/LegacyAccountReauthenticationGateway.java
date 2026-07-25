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
import space.minecraftstl.xyml.auth.Account;
import space.minecraftstl.xyml.auth.AuthInfo;
import space.minecraftstl.xyml.auth.ClassicAccount;
import space.minecraftstl.xyml.auth.OAuthAccount;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.setting.Accounts;
import space.minecraftstl.xyml.ui.swing.legacy.LegacyStateDispatcher;
import space.minecraftstl.xyml.util.StringUtils;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/// Bridges the toolkit-neutral reauthentication workflow to existing account objects and storage.
///
/// Account lookup, read-only inspection, backup, list refresh, and synchronous private-data persistence
/// are confined to [LegacyStateDispatcher]. Network authentication remains on the caller executor.
@NotNullByDefault
public final class LegacyAccountReauthenticationGateway implements AccountReauthenticationGateway {
    /// Temporary OAuth event subscriptions, or null outside active OAuth authentication.
    private final AtomicReference<@Nullable OAuthSubscriptions> activeOAuthSubscriptions = new AtomicReference<>();

    /// Resolves an exact stable account and captures only prompt-safe metadata.
    @Override
    public AccountReauthenticationTarget describe(String accountId) {
        Objects.requireNonNull(accountId, "accountId");
        AtomicReference<AccountReauthenticationTarget> result = new AtomicReference<>();
        LegacyStateDispatcher.executeAndWait(() -> {
            Account account = findAccountOnEventThread(accountId);
            String profileName = account.getProfileName();
            String displayName = account instanceof ClassicAccount classic
                    ? classic.getLoginName()
                    : StringUtils.isBlank(profileName) ? accountId : profileName;
            AccountReauthenticationKind kind = account instanceof ClassicAccount
                    ? AccountReauthenticationKind.CLASSIC_PASSWORD
                    : account instanceof OAuthAccount
                            ? AccountReauthenticationKind.OAUTH_DEVICE_CODE
                            : AccountReauthenticationKind.DIRECT;
            result.set(new AccountReauthenticationTarget(
                    accountId,
                    displayName,
                    kind,
                    account.isPortable(),
                    Accounts.isAccountFilesReadOnly(account)));
        });
        return Objects.requireNonNull(result.get(), "legacy reauthentication target");
    }

    /// Resolves and reauthenticates one classic account on the caller executor.
    @Override
    public PreparedReauthentication authenticateClassic(
            AccountReauthenticationTarget target,
            String password) throws Exception {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(password, "password");
        Account account = resolveAccount(target.accountId());
        if (!(account instanceof ClassicAccount classicAccount)) {
            throw new IllegalArgumentException("Account is no longer a classic account: " + target.accountId());
        }
        AuthInfo authInfo = classicAccount.logInWithPassword(password);
        return new LegacyPreparedReauthentication(account, authInfo);
    }

    /// Runs existing OAuth expiry recovery while forwarding temporary device-code events.
    @Override
    public PreparedReauthentication authenticateOAuth(
            AccountReauthenticationTarget target,
            Consumer<AccountReauthenticationNotice> progress) throws Exception {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(progress, "progress");
        Account account = resolveAccount(target.accountId());
        if (!(account instanceof OAuthAccount oauthAccount)) {
            throw new IllegalArgumentException("Account is no longer an OAuth account: " + target.accountId());
        }
        synchronized (LegacyMicrosoftOAuthLock.monitor()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("OAuth reauthentication was cancelled before subscription");
            }
            OAuthSubscriptions subscriptions = new OAuthSubscriptions(
                    Accounts.OAUTH_CALLBACK.onOpenBrowserAuthorizationCode.subscribe(
                            event -> progress.accept(AccountReauthenticationNotice.browserAuthorization(
                                    event.getUrl()))),
                    Accounts.OAUTH_CALLBACK.onGrantDeviceCode.subscribe(
                            event -> progress.accept(AccountReauthenticationNotice.deviceAuthorization(
                                    event.getVerificationUri(),
                                    event.getUserCode()))),
                    Accounts.OAUTH_CALLBACK.onLoginCompletedDeviceCode.subscribe(
                            event -> progress.accept(AccountReauthenticationNotice.authorizationCompleted())));
            if (!activeOAuthSubscriptions.compareAndSet(null, subscriptions)) {
                subscriptions.close();
                throw new IllegalStateException("Another OAuth reauthentication subscription is active");
            }
            try (subscriptions) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("OAuth reauthentication was cancelled after subscription");
                }
                AuthInfo authInfo = oauthAccount.logInWhenCredentialsExpired();
                return new LegacyPreparedReauthentication(account, authInfo);
            } finally {
                activeOAuthSubscriptions.compareAndSet(subscriptions, null);
            }
        }
    }

    /// Repeats ordinary authentication for an account without a specialized recovery contract.
    @Override
    public PreparedReauthentication authenticateDirect(AccountReauthenticationTarget target) throws Exception {
        Objects.requireNonNull(target, "target");
        Account account = resolveAccount(target.accountId());
        return new LegacyPreparedReauthentication(account, account.logIn());
    }

    /// Immediately unregisters temporary OAuth event listeners during cancellation.
    @Override
    public void cancelActiveAuthentication() {
        @Nullable OAuthSubscriptions subscriptions = activeOAuthSubscriptions.getAndSet(null);
        if (subscriptions != null) {
            subscriptions.close();
        }
    }

    /// Forces compatible storage when authorized and synchronously republishes the changed account entry.
    @Override
    public void persist(
            PreparedReauthentication prepared,
            boolean allowReadOnlyOverwrite) throws Exception {
        LegacyPreparedReauthentication legacy = requirePrepared(prepared);
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        LegacyStateDispatcher.executeAndWait(() -> {
            try {
                int index = identityIndexOnEventThread(legacy.account());
                if (index < 0) {
                    throw new IllegalStateException(
                            "Reauthenticated account is no longer registered: " + legacy.accountId());
                }
                if (Accounts.isAccountFilesReadOnly(legacy.account())) {
                    if (!allowReadOnlyOverwrite) {
                        throw new IllegalStateException(
                                "Account storage became read-only before credentials could be persisted");
                    }
                    Accounts.forceOverwriteAccountFiles(legacy.account());
                }

                // A set notification synchronously serializes changed private data even when Account.invalidate()
                // is still queued behind this operation on the launcher state event thread.
                Accounts.getAccounts().set(index, legacy.account());
            } catch (IOException | RuntimeException exception) {
                failure.set(exception);
            }
        });
        @Nullable Throwable persistenceFailure = failure.get();
        if (persistenceFailure instanceof Exception exception) {
            throw exception;
        }
        if (persistenceFailure instanceof Error error) {
            throw error;
        }
    }

    /// Delegates known failures to existing localized account error mapping.
    @Override
    public String localizeFailure(Throwable failure) {
        Throwable unwrapped = unwrap(Objects.requireNonNull(failure, "failure"));
        if (unwrapped instanceof Exception exception) {
            @Nullable String localized = Accounts.localizeErrorMessage(exception);
            if (localized != null && !localized.isBlank()) {
                return localized;
            }
        }
        @Nullable String message = unwrapped.getLocalizedMessage();
        return message == null || message.isBlank()
                ? unwrapped.getClass().getSimpleName()
                : message;
    }

    /// Resolves an account object by stable ID through the legacy dispatcher.
    ///
    /// @param accountId stable account identifier
    /// @return current account object
    private static Account resolveAccount(String accountId) {
        AtomicReference<Account> result = new AtomicReference<>();
        LegacyStateDispatcher.executeAndWait(() -> result.set(findAccountOnEventThread(accountId)));
        return Objects.requireNonNull(result.get(), "legacy account");
    }

    /// Finds an exact stable account while on the legacy event thread.
    ///
    /// @param accountId stable account identifier
    /// @return current account object
    private static Account findAccountOnEventThread(String accountId) {
        LegacyStateDispatcher.requireEventThread();
        for (Account account : Accounts.getAccounts()) {
            if (account.getAccountID().toString().equals(accountId)) {
                return account;
            }
        }
        throw new IllegalArgumentException("Unknown account: " + accountId);
    }

    /// Finds an account by identity to reject replacement during network authentication.
    ///
    /// @param account exact authenticated account object
    /// @return list index, or -1 when stale
    private static int identityIndexOnEventThread(Account account) {
        LegacyStateDispatcher.requireEventThread();
        for (int index = 0; index < Accounts.getAccounts().size(); index++) {
            if (Accounts.getAccounts().get(index) == account) {
                return index;
            }
        }
        return -1;
    }

    /// Rejects prepared results created by another gateway.
    ///
    /// @param prepared prepared result
    /// @return validated legacy result
    private static LegacyPreparedReauthentication requirePrepared(PreparedReauthentication prepared) {
        Objects.requireNonNull(prepared, "prepared");
        if (prepared instanceof LegacyPreparedReauthentication legacy) {
            return legacy;
        }
        throw new IllegalArgumentException("Prepared reauthentication was not created by this gateway");
    }

    /// Removes common asynchronous wrappers before localization.
    ///
    /// @param failure original failure
    /// @return meaningful cause
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = Objects.requireNonNull(current.getCause());
        }
        return current;
    }

    /// Idempotently owned temporary OAuth callback subscriptions.
    ///
    /// @param browser browser-authorization subscription
    /// @param device device-code subscription
    /// @param completed device-completion subscription
    @NotNullByDefault
    private record OAuthSubscriptions(
            Subscription browser,
            Subscription device,
            Subscription completed) implements AutoCloseable {
        /// Validates one subscription group.
        private OAuthSubscriptions {
            Objects.requireNonNull(browser, "browser");
            Objects.requireNonNull(device, "device");
            Objects.requireNonNull(completed, "completed");
        }

        /// Unregisters all subscriptions; each subscription is independently idempotent.
        @Override
        public void close() {
            @Nullable Throwable failure = null;
            try {
                browser.close();
            } catch (RuntimeException exception) {
                failure = exception;
            }
            try {
                device.close();
            } catch (RuntimeException exception) {
                failure = accumulate(failure, exception);
            }
            try {
                completed.close();
            } catch (RuntimeException exception) {
                failure = accumulate(failure, exception);
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
        }

        /// Adds one close failure without self-suppression.
        ///
        /// @param current current failure, or null
        /// @param additional additional failure
        /// @return primary failure
        private static Throwable accumulate(
                @Nullable Throwable current,
                Throwable additional) {
            if (current == null) {
                return additional;
            }
            if (current != additional) {
                current.addSuppressed(additional);
            }
            return current;
        }
    }

    /// Private credential-bearing successful result.
    ///
    /// @param account exact mutated legacy account
    /// @param authInfo launch authentication data
    @NotNullByDefault
    private record LegacyPreparedReauthentication(
            Account account,
            AuthInfo authInfo) implements PreparedReauthentication {
        /// Validates one successful result.
        private LegacyPreparedReauthentication {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(authInfo, "authInfo");
        }

        /// Returns the stable account identifier.
        @Override
        public String accountId() {
            return account.getAccountID().toString();
        }
    }
}
