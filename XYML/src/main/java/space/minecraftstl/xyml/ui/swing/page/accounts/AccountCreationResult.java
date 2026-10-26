// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Plain successful result published after the account is committed and selected.
///
/// @param accountId stable account identifier
/// @param displayName authenticated profile name
/// @param method authentication method
/// @param portable whether the account was stored in workspace-local files
@NotNullByDefault
public record AccountCreationResult(
        String accountId,
        String displayName,
        AccountCreationMethod method,
        boolean portable) {
    /// Validates one immutable successful result.
    public AccountCreationResult {
        accountId = Objects.requireNonNull(accountId, "accountId");
        displayName = Objects.requireNonNull(displayName, "displayName");
        method = Objects.requireNonNull(method, "method");
    }
}
