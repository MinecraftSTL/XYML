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
package space.minecraftstl.xyml.ui.swing.page.home;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable selected account and instance identity plus presentation from a launcher state store.
///
/// @param accountId stable selected account ID, or empty for none
/// @param gameDirectoryId stable selected game-directory ID, or empty for none
/// @param instanceId stable selected instance ID within the game directory, or empty for none
/// @param accountName selected account profile name, or empty for none
/// @param accountDetail selected account provider detail
/// @param instanceName selected instance display name, or empty when unavailable
/// @param instanceDetail selected game-directory detail
@NotNullByDefault
public record HomeSelectionState(
        String accountId,
        String gameDirectoryId,
        String instanceId,
        String accountName,
        String accountDetail,
        String instanceName,
        String instanceDetail) {
    /// Validates one selection state.
    public HomeSelectionState {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(gameDirectoryId, "gameDirectoryId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(accountName, "accountName");
        Objects.requireNonNull(accountDetail, "accountDetail");
        Objects.requireNonNull(instanceName, "instanceName");
        Objects.requireNonNull(instanceDetail, "instanceDetail");
    }
}
