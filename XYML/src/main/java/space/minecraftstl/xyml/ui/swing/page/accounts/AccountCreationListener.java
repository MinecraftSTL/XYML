// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Receives account-operation events on the injected UI dispatcher.
@NotNullByDefault
public interface AccountCreationListener {
    /// Handles one credential-free progress transition.
    ///
    /// @param notice progress notice
    void onProgress(AccountCreationNotice notice);

    /// Handles a committed account.
    ///
    /// @param result successful result
    void onSucceeded(AccountCreationResult result);

    /// Handles cancellation from the form, a prompt, or explicit operation cancellation.
    void onCancelled();

    /// Handles a localized authentication or persistence failure.
    ///
    /// @param localizedMessage localized user-visible message
    /// @param failure original failure for diagnostics
    void onFailed(String localizedMessage, Throwable failure);
}
