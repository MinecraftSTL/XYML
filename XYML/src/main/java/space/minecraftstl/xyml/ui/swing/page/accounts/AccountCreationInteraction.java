// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Blocking user-interaction boundary invoked from the account worker, never from the Swing EDT.
///
/// A Swing implementation may synchronously marshal each prompt to the EDT. [#cancelPendingInteraction()]
/// must unblock any current prompt so workflow cancellation can complete promptly.
@NotNullByDefault
public interface AccountCreationInteraction {
    /// Confirms continued use of an offline name outside vanilla limits.
    ///
    /// @param username requested offline username
    /// @return true to continue, false to return to the form
    boolean confirmInvalidOfflineUsername(String username);

    /// Confirms backup and destructive overwrite of newer account files.
    ///
    /// @param portable true for workspace-local files, false for shared user files
    /// @return true to back up and overwrite, false to return to the form
    boolean confirmReadOnlyStorage(boolean portable);

    /// Selects one role when an authentication response contains multiple profiles.
    ///
    /// @param roles immutable available roles
    /// @return selected profile identifier text
    /// @throws AccountCreationCancelledException when the prompt is closed or cancelled
    String selectRole(@Unmodifiable List<AccountRoleOption> roles)
            throws AccountCreationCancelledException;

    /// Closes and unblocks the currently visible interaction, if any.
    void cancelPendingInteraction();
}
