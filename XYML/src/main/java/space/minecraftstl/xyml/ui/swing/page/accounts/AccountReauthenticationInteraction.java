// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Blocking prompt boundary invoked by the reauthentication worker and progress boundary invoked on the UI thread.
@NotNullByDefault
public interface AccountReauthenticationInteraction {
    /// Confirms backup-and-overwrite before any credentials are mutated.
    ///
    /// @param target read-only account target
    /// @return true to permit successful persistence
    boolean confirmReadOnlyStorage(AccountReauthenticationTarget target);

    /// Requests a password, optionally showing the previous localized authentication error.
    ///
    /// The caller clears the returned mutable array immediately after constructing the transient password string.
    ///
    /// @param target classic account target
    /// @param localizedError previous localized error, or null for the first attempt
    /// @return newly allocated password characters
    /// @throws AccountReauthenticationCancelledException when the user cancels
    char[] requestPassword(
            AccountReauthenticationTarget target,
            @Nullable String localizedError) throws AccountReauthenticationCancelledException;

    /// Confirms retry after one localized OAuth failure.
    ///
    /// @param target OAuth target
    /// @param localizedError localized failure message
    /// @return true to retry, false to cancel
    boolean confirmOAuthRetry(AccountReauthenticationTarget target, String localizedError);

    /// Presents one credential-free progress transition on the UI thread.
    ///
    /// @param target active target
    /// @param notice progress notice
    void onProgress(AccountReauthenticationTarget target, AccountReauthenticationNotice notice);

    /// Presents one terminal localized failure.
    ///
    /// @param target active target
    /// @param localizedError localized failure message
    void showFailure(AccountReauthenticationTarget target, String localizedError);

    /// Closes the current password, confirmation, or OAuth progress UI.
    void closeCurrentInteraction();
}
