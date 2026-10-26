// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Terminal reauthentication failure carrying an already localized message.
@NotNullByDefault
public final class AccountReauthenticationException extends Exception {
    /// Creates a localized terminal failure.
    ///
    /// @param localizedMessage localized user-visible message
    /// @param cause original failure
    public AccountReauthenticationException(String localizedMessage, Throwable cause) {
        super(localizedMessage, cause);
    }
}
