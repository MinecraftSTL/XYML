// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Signals that modifying one account requires explicit backup-and-overwrite consent.
@NotNullByDefault
public final class AccountStorageOverwriteRequiredException extends IllegalStateException {
    /// Stable identifier of the account whose storage is read-only.
    private final String accountId;

    /// Creates a read-only storage signal for one exact account.
    ///
    /// @param accountId stable account identifier
    /// @param message localized explanation of the incompatible storage version
    AccountStorageOverwriteRequiredException(String accountId, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.accountId = Objects.requireNonNull(accountId, "accountId");
    }

    /// Returns the stable account identifier associated with this signal.
    ///
    /// @return stable account identifier
    public String accountId() {
        return accountId;
    }
}
