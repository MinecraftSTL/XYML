// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Plain account metadata needed before credential-expiry recovery starts.
///
/// @param accountId stable account identifier
/// @param displayName profile or login display name
/// @param kind recovery mechanism
/// @param portable whether credentials belong to workspace-local files
/// @param storageReadOnly whether successful credentials require confirmed backup-and-overwrite
@NotNullByDefault
public record AccountReauthenticationTarget(
        String accountId,
        String displayName,
        AccountReauthenticationKind kind,
        boolean portable,
        boolean storageReadOnly) {
    /// Validates one immutable target snapshot.
    public AccountReauthenticationTarget {
        accountId = Objects.requireNonNull(accountId, "accountId");
        displayName = Objects.requireNonNull(displayName, "displayName");
        kind = Objects.requireNonNull(kind, "kind");
    }
}
