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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.List;

/// Shallow source result containing no parsed pack metadata.
///
/// Enabled installed packs occupy the leading paths in descending priority order. The remaining
/// paths are disabled packs in deterministic file-name order.
///
/// @param supported whether the Minecraft instance supports resource packs
/// @param paths candidate direct children in display order, empty when unsupported
/// @param enabledPathCount number of leading paths whose identifiers are enabled
@NotNullByDefault
record ResourcePackCatalogIndex(
        boolean supported,
        @Unmodifiable List<Path> paths,
        int enabledPathCount) {
    /// Stores a defensive path-list copy and validates supported counts.
    ResourcePackCatalogIndex {
        paths = List.copyOf(paths);
        if (enabledPathCount < 0 || enabledPathCount > paths.size()) {
            throw new IllegalArgumentException("enabledPathCount must be inside paths");
        }
        if (!supported && (!paths.isEmpty() || enabledPathCount != 0)) {
            throw new IllegalArgumentException("Unsupported index must not contain paths or enabled entries");
        }
    }

    /// Creates one index with no known enabled prefix.
    ///
    /// @param supported whether the instance supports resource packs
    /// @param paths deterministic candidate paths
    ResourcePackCatalogIndex(boolean supported, @Unmodifiable List<Path> paths) {
        this(supported, paths, 0);
    }
}
