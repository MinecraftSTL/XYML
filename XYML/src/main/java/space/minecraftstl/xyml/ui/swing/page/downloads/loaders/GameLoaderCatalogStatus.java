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
package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes the toolkit-neutral lifecycle of one selected loader catalog.
@NotNullByDefault
public enum GameLoaderCatalogStatus {
    /// No Minecraft version has been explicitly selected.
    AWAITING_GAME_VERSION,

    /// A Minecraft version is selected but no compatible loader catalog is selected.
    AWAITING_LOADER,

    /// A game version and loader kind are selected but no refresh has been requested.
    IDLE,

    /// One explicit VersionList refresh is in flight.
    LOADING,

    /// The latest explicit refresh completed and supplied immutable loader versions.
    READY,

    /// The latest explicit refresh failed; details are available from the snapshot.
    FAILED
}
