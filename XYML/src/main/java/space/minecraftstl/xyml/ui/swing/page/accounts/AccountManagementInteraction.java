/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
