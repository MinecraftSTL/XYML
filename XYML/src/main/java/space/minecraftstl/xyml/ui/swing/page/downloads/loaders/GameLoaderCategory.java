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

/// Classifies one selectable loader entry without imposing Swing presentation details.
///
/// The categories intentionally preserve the distinctions exposed by the historical installer:
/// companion APIs, Legacy Fabric entries, and Cleanroom are not folded into a generic loader.
@NotNullByDefault
public enum GameLoaderCategory {
    /// A current-generation mod loader.
    MOD_LOADER,

    /// An API add-on intended to accompany a compatible mod loader.
    API,

    /// A loader retained for older Minecraft releases.
    LEGACY_LOADER,

    /// An API add-on intended for a legacy loader.
    LEGACY_API,

    /// The 1.12.2-era Cleanroom loader.
    CLEANROOM,

    /// A client optimization component rather than a conventional mod loader.
    OPTIMIZATION
}
