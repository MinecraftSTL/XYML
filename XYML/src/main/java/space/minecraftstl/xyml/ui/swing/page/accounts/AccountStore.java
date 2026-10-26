// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;

/// Supplies account descriptors and persists account selection without exposing a UI toolkit.
@NotNullByDefault
public interface AccountStore {
    /// Returns the latest immutable account state.
    ///
    /// @return current account descriptors and selection
    AccountStoreState snapshot();

    /// Registers for future account-store transitions on the publishing thread.
    ///
    /// @param listener account-state transition listener
    /// @return independently cancellable listener registration
    Subscription subscribe(ValueChangeListener<AccountStoreState> listener);

    /// Persists selection of one stable account identifier.
    ///
    /// @param accountId stable account identifier
    void selectAccount(String accountId);

    /// Permanently removes one account by stable identifier.
    ///
    /// Implementations must leave selection empty or pointing to a remaining account before publishing the
    /// resulting structural transition.
    ///
    /// @param accountId stable account identifier
    /// @param allowReadOnlyOverwrite whether confirmed backup-and-overwrite may make newer storage writable
    /// @throws AccountStorageOverwriteRequiredException when storage is read-only and overwrite is not allowed
    void removeAccount(String accountId, boolean allowReadOnlyOverwrite);

    /// Moves one account to a final source index without crossing its portable/global storage group.
    ///
    /// @param accountId stable account identifier
    /// @param targetIndex final zero-based source index after the move
    /// @param allowReadOnlyOverwrite whether confirmed backup-and-overwrite may make storage writable
    /// @throws IllegalArgumentException when the account or target ordering is invalid
    /// @throws AccountStorageOverwriteRequiredException when storage is read-only and overwrite is not allowed
    void moveAccount(String accountId, int targetIndex, boolean allowReadOnlyOverwrite);
}
