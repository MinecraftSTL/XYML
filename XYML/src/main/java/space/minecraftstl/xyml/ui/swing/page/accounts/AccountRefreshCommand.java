// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.auth.AuthInfo;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/// Refreshes one persisted account by stable ID without transferring credential-bearing results to the UI.
@FunctionalInterface
@NotNullByDefault
public interface AccountRefreshCommand {
    /// Adapts the shared reauthentication service and closes its successful `AuthInfo` result.
    ///
    /// The returned command does not own or close the supplied service. Authentication, prompts, network access,
    /// and persistence retain the service's existing executor and UI-dispatch behavior.
    ///
    /// @param reauthentication shared application-owned reauthentication service
    /// @return refresh command suitable for the account page
    static AccountRefreshCommand from(AccountReauthentication reauthentication) {
        Objects.requireNonNull(reauthentication, "reauthentication");
        return accountId -> reauthentication.reauthenticate(accountId)
                .thenAccept(AccountRefreshCommand::closeAuthInfo);
    }

    /// Creates a deterministic command for compositions that do not expose account refresh.
    ///
    /// @return command whose invocations fail immediately
    static AccountRefreshCommand unavailable() {
        return accountId -> CompletableFuture.failedFuture(
                new UnsupportedOperationException("Account refresh is unavailable"));
    }

    /// Starts a nonblocking refresh for one exact account.
    ///
    /// @param accountId stable persisted account identifier
    /// @return completion after authentication data has been persisted and released
    CompletionStage<Void> refresh(String accountId);

    /// Releases credential-bearing authentication data after page-initiated refresh.
    ///
    /// @param authInfo successful authentication result
    private static void closeAuthInfo(AuthInfo authInfo) {
        Objects.requireNonNull(authInfo, "authInfo");
        try {
            authInfo.close();
        } catch (Exception failure) {
            throw new CompletionException("Failed to release refreshed authentication data", failure);
        }
    }
}
