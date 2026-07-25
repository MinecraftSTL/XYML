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
