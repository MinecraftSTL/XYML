// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

import java.awt.Component;

/// Isolates destructive confirmation, clipboard access, and terminal account-action errors from the page.
@NotNullByDefault
interface AccountManagementInteraction {
    /// Confirms permanent removal of one account.
    ///
    /// @param owner native dialog owner
    /// @param title localized confirmation title
    /// @param message localized destructive-action message
    /// @return whether removal may proceed
    boolean confirmRemoval(Component owner, String title, String message);

    /// Confirms backing up and overwriting account files written by a newer launcher.
    ///
    /// @param owner native dialog owner
    /// @return whether destructive backup-and-overwrite may proceed
    boolean confirmReadOnlyOverwrite(Component owner);

    /// Copies exact plain text to the platform clipboard.
    ///
    /// @param text text to copy
    void copyText(String text);

    /// Presents one terminal account-action failure.
    ///
    /// @param owner native dialog owner
    /// @param title localized error title
    /// @param message localized or diagnostic failure detail
    void showFailure(Component owner, String title, String message);
}
