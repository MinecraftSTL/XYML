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

import java.util.function.Consumer;

/// Injectable account lookup, authentication, localization, and persistence boundary.
@NotNullByDefault
public interface AccountReauthenticationGateway {
    /// Resolves one stable account to plain prompt metadata.
    ///
    /// @param accountId stable account identifier
    /// @return immutable target metadata
    AccountReauthenticationTarget describe(String accountId);

    /// Reauthenticates a classic account with a newly entered password.
    ///
    /// @param target resolved target
    /// @param password password text retained only for this call
    /// @return prepared successful result
    /// @throws Exception when authentication fails
    PreparedReauthentication authenticateClassic(
            AccountReauthenticationTarget target,
            String password) throws Exception;

    /// Reauthenticates an OAuth account with its interactive expiry-recovery method.
    ///
    /// @param target resolved target
    /// @param progress credential-free OAuth progress sink
    /// @return prepared successful result
    /// @throws Exception when authentication fails
    PreparedReauthentication authenticateOAuth(
            AccountReauthenticationTarget target,
            Consumer<AccountReauthenticationNotice> progress) throws Exception;

    /// Repeats ordinary login for an account requiring no special prompt.
    ///
    /// @param target resolved target
    /// @return prepared successful result
    /// @throws Exception when authentication fails
    PreparedReauthentication authenticateDirect(AccountReauthenticationTarget target) throws Exception;

    /// Immediately releases temporary callback subscriptions owned by active authentication.
    ///
    /// This method may be called from any thread during cancellation and must be idempotent.
    void cancelActiveAuthentication();

    /// Persists changed private data through the launcher-state dispatcher.
    ///
    /// @param prepared successful result created by this gateway
    /// @param allowReadOnlyOverwrite whether the user confirmed backup-and-overwrite
    /// @throws Exception when persistence fails or the account became stale
    void persist(PreparedReauthentication prepared, boolean allowReadOnlyOverwrite) throws Exception;

    /// Converts a failure to localized user-visible text.
    ///
    /// @param failure original failure
    /// @return localized non-empty message
    String localizeFailure(Throwable failure);
}
