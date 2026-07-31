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
