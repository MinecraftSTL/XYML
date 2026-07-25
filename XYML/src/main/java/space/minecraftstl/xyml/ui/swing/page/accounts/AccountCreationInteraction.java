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
