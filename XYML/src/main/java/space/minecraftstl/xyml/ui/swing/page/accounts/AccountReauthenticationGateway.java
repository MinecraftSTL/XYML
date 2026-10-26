// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
