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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.auth.Account;
import space.minecraftstl.xyml.auth.AccountFactory;
import space.minecraftstl.xyml.auth.CharacterSelector;
import space.minecraftstl.xyml.auth.NoSelectedCharacterException;
import space.minecraftstl.xyml.auth.OAuth;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorServer;
import space.minecraftstl.xyml.auth.offline.OfflineAccountFactory;
import space.minecraftstl.xyml.auth.yggdrasil.GameProfile;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.setting.Accounts;
import space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher;
import space.minecraftstl.xyml.util.i18n.LocaleUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static space.minecraftstl.xyml.setting.SettingsManager.getAuthlibInjectorServers;
import static space.minecraftstl.xyml.setting.SettingsManager.settings;
import static space.minecraftstl.xyml.setting.SettingsManager.userSettings;

/// Adapts existing account factories and launcher-owned account storage to the Swing workflow.
///
/// Factory authentication runs on the coordinator's caller-owned executor. Every preference read and
/// account/storage mutation is serialized through [LauncherStateDispatcher]. No UI component type crosses
/// this adapter's public boundary.
@NotNullByDefault
public final class LauncherAccountCreationGateway implements AccountCreationGateway {
    /// Reads the launcher preference and applies the current Microsoft-only policy on the Swing EDT.
    @Override
    public AccountCreationMethod preferredMethod() {
        AtomicReference<AccountCreationMethod> result = new AtomicReference<>();
        LauncherStateDispatcher.executeAndWait(() -> {
            if (isMicrosoftOnlyOnEventThread()) {
                result.set(AccountCreationMethod.MICROSOFT);
                return;
            }
            @Nullable String preferred = settings().preferredLoginTypeProperty().get();
            result.set(switch (preferred == null ? "" : preferred) {
                case "microsoft" -> AccountCreationMethod.MICROSOFT;
                case "authlibInjector" -> AccountCreationMethod.AUTHLIB_INJECTOR;
                case "offline" -> AccountCreationMethod.OFFLINE;
                default -> AccountCreationMethod.OFFLINE;
            });
        });
        return Objects.requireNonNull(result.get(), "launcher preferred account method");
    }

    /// Reads the dynamic Microsoft-only policy on the Swing EDT.
    @Override
    public boolean isMicrosoftOnly() {
        AtomicReference<Boolean> result = new AtomicReference<>();
        LauncherStateDispatcher.executeAndWait(() -> result.set(isMicrosoftOnlyOnEventThread()));
        return Boolean.TRUE.equals(result.get());
    }

    /// Persists the selected login type through the launcher event dispatcher.
    @Override
    public void storePreferredMethod(AccountCreationMethod method) {
        Objects.requireNonNull(method, "method");
        LauncherStateDispatcher.executeAndWait(() -> settings().preferredLoginTypeProperty().set(
                switch (method) {
                    case OFFLINE -> "offline";
                    case MICROSOFT -> "microsoft";
                    case AUTHLIB_INJECTOR -> "authlibInjector";
                }));
    }

    /// Captures configured authlib-injector servers as immutable plain values.
    @Override
    public @Unmodifiable List<AuthlibServerOption> availableAuthlibServers() {
        AtomicReference<List<AuthlibServerOption>> result = new AtomicReference<>();
        LauncherStateDispatcher.executeAndWait(() -> {
            List<AuthlibServerOption> options = new ArrayList<>(getAuthlibInjectorServers().size());
            for (AuthlibInjectorServer server : getAuthlibInjectorServers()) {
                options.add(new AuthlibServerOption(
                        server.getUrl(),
                        server.getName(),
                        !server.isNonEmailLogin()));
            }
            result.set(List.copyOf(options));
        });
        return Objects.requireNonNull(result.get(), "launcher server snapshot");
    }

    /// Authenticates one account without mutating the observable account list.
    @Override
    public PreparedAccount authenticate(
            AccountCreationRequest request,
            AccountRoleSelector roleSelector,
            Consumer<AccountCreationNotice> progress) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(roleSelector, "roleSelector");
        Objects.requireNonNull(progress, "progress");
        Account account = switch (request.method()) {
            case OFFLINE -> authenticateOffline(request, roleSelector, progress);
            case MICROSOFT -> authenticateMicrosoft(request, roleSelector, progress);
            case AUTHLIB_INJECTOR -> authenticateAuthlibInjector(request, roleSelector, progress);
        };
        return new PreparedLauncherAccount(account, request.method(), request.portable());
    }

    /// Reads the selected target's compatibility state on the Swing EDT.
    @Override
    public boolean isTargetReadOnly(PreparedAccount account) {
        PreparedLauncherAccount prepared = requirePreparedAccount(account);
        AtomicReference<Boolean> result = new AtomicReference<>();
        LauncherStateDispatcher.executeAndWait(() ->
                result.set(Accounts.isAccountFilesReadOnly(prepared.portable())));
        return Boolean.TRUE.equals(result.get());
    }

    /// Backs up and overwrites the selected target account files on the Swing EDT.
    @Override
    public void forceOverwriteTarget(PreparedAccount account) throws IOException {
        PreparedLauncherAccount prepared = requirePreparedAccount(account);
        AtomicReference<@Nullable IOException> failure = new AtomicReference<>();
        LauncherStateDispatcher.executeAndWait(() -> {
            try {
                Accounts.forceOverwriteAccountFiles(prepared.portable());
            } catch (IOException exception) {
                failure.set(exception);
            }
        });
        @Nullable IOException exception = failure.get();
        if (exception != null) {
            throw exception;
        }
    }

    /// Commits and selects an authenticated account through the launcher dispatcher.
    @Override
    public void commitAndSelect(PreparedAccount account) {
        PreparedLauncherAccount prepared = requirePreparedAccount(account);
        LauncherStateDispatcher.executeAndWait(() -> {
            Account authenticatedAccount = prepared.account();
            authenticatedAccount.setPortable(prepared.portable());
            int oldIndex = Accounts.getAccounts().indexOf(authenticatedAccount);
            if (oldIndex < 0) {
                Accounts.getAccounts().add(authenticatedAccount);
            } else {
                Accounts.getAccounts().set(oldIndex, authenticatedAccount);
            }
            Accounts.setSelectedAccount(authenticatedAccount);
        });
    }

    /// Delegates known authentication failures to existing localized error mapping.
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

    /// Creates an offline account using the existing factory on the caller executor.
    ///
    /// @param request offline request
    /// @param roleSelector profile selector forwarded to the account factory
    /// @param progress progress sink
    /// @return authenticated offline account
    private static Account authenticateOffline(
            AccountCreationRequest request,
            AccountRoleSelector roleSelector,
            Consumer<AccountCreationNotice> progress) throws Exception {
        OfflineAccountFactory.AdditionalData data = new OfflineAccountFactory.AdditionalData(
                request.offlineUuid(),
                null);
        return Accounts.FACTORY_OFFLINE.create(
                toCharacterSelector(roleSelector),
                Objects.requireNonNull(request.username(), "offline username"),
                "",
                progressCallback(progress),
                data);
    }

    /// Creates a Microsoft account while forwarding temporary OAuth events as plain progress notices.
    ///
    /// @param request Microsoft request
    /// @param roleSelector profile selector forwarded to the account factory
    /// @param progress progress sink
    /// @return authenticated Microsoft account
    private Account authenticateMicrosoft(
            AccountCreationRequest request,
            AccountRoleSelector roleSelector,
            Consumer<AccountCreationNotice> progress) throws Exception {
        MicrosoftAccountLoginMode loginMode = Objects.requireNonNull(
                request.microsoftLoginMode(),
                "Microsoft login mode");
        OAuth.GrantFlow grantFlow = loginMode == MicrosoftAccountLoginMode.BROWSER
                ? OAuth.GrantFlow.AUTHORIZATION_CODE
                : OAuth.GrantFlow.DEVICE;
        synchronized (MicrosoftOAuthLock.monitor()) {
            try (Subscription browser = Accounts.OAUTH_CALLBACK.onOpenBrowserAuthorizationCode.subscribe(
                    event -> progress.accept(AccountCreationNotice.browserAuthorization(event.getUrl())));
                 Subscription device = Accounts.OAUTH_CALLBACK.onGrantDeviceCode.subscribe(
                         event -> progress.accept(AccountCreationNotice.deviceAuthorization(
                                 event.getVerificationUri(),
                                 event.getUserCode())));
                 Subscription completed = Accounts.OAUTH_CALLBACK.onLoginCompletedDeviceCode.subscribe(
                         event -> progress.accept(AccountCreationNotice.authorizationCompleted()))) {
                return Accounts.FACTORY_MICROSOFT.create(
                        toCharacterSelector(roleSelector),
                        "",
                        "",
                        progressCallback(progress),
                        grantFlow);
            }
        }
    }

    /// Creates an authlib-injector account against an exact configured server URL.
    ///
    /// @param request authlib-injector request
    /// @param roleSelector Swing role-selection boundary
    /// @param progress progress sink
    /// @return authenticated authlib-injector account
    private static Account authenticateAuthlibInjector(
            AccountCreationRequest request,
            AccountRoleSelector roleSelector,
            Consumer<AccountCreationNotice> progress) throws Exception {
        String serverUrl = Objects.requireNonNull(request.authlibServerUrl(), "authlib server URL");
        AuthlibInjectorServer server = findAuthlibServer(serverUrl);
        AccountFactory<?> factory = Accounts.getAccountFactoryByAuthlibInjectorServer(server);
        return factory.create(
                toCharacterSelector(roleSelector),
                Objects.requireNonNull(request.username(), "authlib username"),
                Objects.requireNonNull(request.password(), "authlib password"),
                progressCallback(progress),
                null);
    }

    /// Resolves one exact configured server while confined to the Swing EDT.
    ///
    /// @param serverUrl normalized server URL
    /// @return matching configured server
    private static AuthlibInjectorServer findAuthlibServer(String serverUrl) {
        AtomicReference<@Nullable AuthlibInjectorServer> result = new AtomicReference<>();
        LauncherStateDispatcher.executeAndWait(() -> {
            for (AuthlibInjectorServer server : getAuthlibInjectorServers()) {
                if (server.getUrl().equals(serverUrl)) {
                    result.set(server);
                    return;
                }
            }
        });
        @Nullable AuthlibInjectorServer server = result.get();
        if (server == null) {
            throw new IllegalArgumentException("Unknown authlib-injector server: " + serverUrl);
        }
        return server;
    }

    /// Adapts immutable plain role options back to one exact launcher profile.
    ///
    /// @param roleSelector plain role selector
    /// @return launcher character selector
    private static CharacterSelector toCharacterSelector(AccountRoleSelector roleSelector) {
        return (service, profiles) -> {
            @Unmodifiable List<AccountRoleOption> options = profiles.stream()
                    .map(profile -> new AccountRoleOption(profile.getId(), profile.getName()))
                    .toList();
            String selectedId = roleSelector.select(options);
            UUID selectedUuid;
            try {
                selectedUuid = UUID.fromString(selectedId);
            } catch (IllegalArgumentException failure) {
                throw new NoSelectedCharacterException();
            }
            for (GameProfile profile : profiles) {
                if (profile.getId().equals(selectedUuid)) {
                    return profile;
                }
            }
            throw new NoSelectedCharacterException();
        };
    }

    /// Converts optional factory stage text into credential-free authentication progress.
    ///
    /// @param progress progress sink
    /// @return factory callback
    private static AccountFactory.ProgressCallback progressCallback(
            Consumer<AccountCreationNotice> progress) {
        return stage -> progress.accept(new AccountCreationNotice(
                AccountCreationNotice.Kind.AUTHENTICATING,
                null,
                null,
                stage));
    }

    /// Rejects prepared accounts created by another gateway implementation.
    ///
    /// @param account prepared account
    /// @return validated launcher prepared account
    private static PreparedLauncherAccount requirePreparedAccount(PreparedAccount account) {
        Objects.requireNonNull(account, "account");
        if (account instanceof PreparedLauncherAccount prepared) {
            return prepared;
        }
        throw new IllegalArgumentException("Prepared account was not created by this gateway");
    }

    /// Removes common asynchronous wrapper failures before localization.
    ///
    /// @param failure original failure
    /// @return deepest meaningful cause
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = Objects.requireNonNull(current.getCause());
        }
        return current;
    }

    /// Reproduces the existing account-page restriction policy while on the Swing EDT.
    ///
    /// @return true when only Microsoft login is permitted
    private static boolean isMicrosoftOnlyOnEventThread() {
        LauncherStateDispatcher.requireEventThread();
        String policy = System.getProperty("xyml.offline.auth.restricted", "auto");
        boolean unrestricted = "false".equals(policy)
                || "auto".equals(policy) && LocaleUtils.IS_CHINA_MAINLAND
                || userSettings().enableOfflineAccountProperty().get();
        return !unrestricted;
    }

    /// Private credential-bearing prepared account retained only by this adapter.
    ///
    /// @param account launcher authenticated account
    /// @param method authentication method
    /// @param portable requested storage location
    @NotNullByDefault
    private record PreparedLauncherAccount(
            Account account,
            AccountCreationMethod method,
            boolean portable) implements PreparedAccount {
        /// Validates one prepared account.
        private PreparedLauncherAccount {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(method, "method");
        }

        /// Returns the generated stable account identifier.
        @Override
        public String accountId() {
            return account.getAccountID().toString();
        }

        /// Returns the authenticated profile name.
        @Override
        public String displayName() {
            return account.getProfileName();
        }
    }
}
