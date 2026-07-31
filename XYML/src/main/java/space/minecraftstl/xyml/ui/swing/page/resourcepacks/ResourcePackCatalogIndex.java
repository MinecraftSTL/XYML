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
/// @param supported whether the Minecraft instance supports resource packs
/// @param paths candidate direct children, empty when unsupported
@NotNullByDefault
record ResourcePackCatalogIndex(
        boolean supported,
        @Unmodifiable List<Path> paths) {
    /// Stores a defensive path-list copy and validates unsupported results.
    ResourcePackCatalogIndex {
        paths = List.copyOf(paths);
        if (!supported && !paths.isEmpty()) {
            throw new IllegalArgumentException("Unsupported index must not contain paths");
        }
    }
}
