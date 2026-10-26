// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.auth.offline.Skin;

import java.util.Optional;

/// Reads and persists offline-account skins without exposing mutable launcher account objects to Swing.
///
/// Implementations only return an entry for an existing [space.minecraftstl.xyml.auth.offline.OfflineAccount].
/// Mutations must update the account's persisted metadata through the owning account store.
@NotNullByDefault
public interface OfflineSkinStore {
    /// Returns the current skin state for one exact offline account.
    ///
    /// @param accountId stable launcher account identifier
    /// @return account skin state, or empty when the account is missing or not offline
    Optional<OfflineSkinSnapshot> snapshot(String accountId);

    /// Replaces the selected offline account's skin configuration.
    ///
    /// Passing null restores the UUID-derived launcher default. Implementations reject non-offline,
    /// missing, and non-persistable accounts instead of reporting a successful in-memory-only update.
    ///
    /// @param accountId stable launcher account identifier
    /// @param skin replacement configuration, or null to restore the default skin
    void setSkin(String accountId, @Nullable Skin skin);
}
