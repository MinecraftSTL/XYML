// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Credential-expiry recovery mechanisms understood by the native workflow.
@NotNullByDefault
public enum AccountReauthenticationKind {
    /// Requests a password for an existing classic account.
    CLASSIC_PASSWORD,

    /// Runs the account's interactive OAuth credential-expiry method.
    OAUTH_DEVICE_CODE,

    /// Repeats the account's ordinary login without an additional credential prompt.
    DIRECT
}
