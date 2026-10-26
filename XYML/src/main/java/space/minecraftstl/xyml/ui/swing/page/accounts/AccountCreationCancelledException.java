// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Signals a deliberate cancellation from a confirmation or role-selection prompt.
@NotNullByDefault
public final class AccountCreationCancelledException extends RuntimeException {
    /// Creates a cancellation with a stable diagnostic message.
    public AccountCreationCancelledException() {
        super("Account creation was cancelled");
    }
}
