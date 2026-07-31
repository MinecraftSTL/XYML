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
