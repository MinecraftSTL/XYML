// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Opaque authenticated account waiting for its storage transaction.
///
/// Implementations may retain credential-bearing launcher state. Callers may only use the plain metadata
/// exposed here and must return the same object to the gateway that created it.
@NotNullByDefault
public interface PreparedAccount {
    /// Returns the generated stable account identifier.
    ///
    /// @return stable identifier text
    String accountId();

    /// Returns the authenticated profile display name.
    ///
    /// @return profile display name
    String displayName();

    /// Returns the authentication method used for this account.
    ///
    /// @return authentication method
    AccountCreationMethod method();

    /// Returns whether the account targets workspace-local files.
    ///
    /// @return true for workspace-local storage
    boolean portable();
}
