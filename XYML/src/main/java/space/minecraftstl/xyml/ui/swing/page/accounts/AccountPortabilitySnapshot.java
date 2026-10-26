// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable storage-location state for one launcher account.
///
/// @param accountId stable launcher account identifier
/// @param portable whether metadata and credentials are stored beside the launcher
/// @param writable whether both source and destination account stores currently accept writes
@NotNullByDefault
public record AccountPortabilitySnapshot(
        String accountId,
        boolean portable,
        boolean writable) {
    /// Validates one account storage-location snapshot.
    public AccountPortabilitySnapshot {
        Objects.requireNonNull(accountId, "accountId");
        if (accountId.isBlank()) {
            throw new IllegalArgumentException("accountId must not be blank");
        }
    }
}
