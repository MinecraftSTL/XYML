// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Signals deliberate cancellation from a native reauthentication prompt.
@NotNullByDefault
public final class AccountReauthenticationCancelledException extends RuntimeException {
    /// Creates a cancellation with a stable diagnostic message.
    public AccountReauthenticationCancelledException() {
        super("Account reauthentication was cancelled");
    }
}
