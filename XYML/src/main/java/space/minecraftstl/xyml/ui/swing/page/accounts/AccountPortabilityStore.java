// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Optional;

/// Moves accounts between launcher-local and user-global persistence without exposing credentials.
@NotNullByDefault
public interface AccountPortabilityStore {
    /// Returns the current storage location for one account.
    ///
    /// @param accountId stable launcher account identifier
    /// @return storage state, or empty when the account disappeared
    Optional<AccountPortabilitySnapshot> portability(String accountId);

    /// Moves one account to the opposite storage location.
    ///
    /// @param accountId stable launcher account identifier
    /// @param allowReadOnlyOverwrite whether confirmed backup-and-overwrite may recover both stores
    /// @throws AccountStorageOverwriteRequiredException when either store is read-only and recovery was not allowed
    void move(String accountId, boolean allowReadOnlyOverwrite);
}
