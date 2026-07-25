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
