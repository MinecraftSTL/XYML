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
import java.util.HashSet;
import java.util.List;

/// Multi-source import request captured before worker submission.
///
/// @param sources normalized absolute source paths
@NotNullByDefault
record ResourcePackImportMutation(
        @Unmodifiable List<Path> sources) implements ResourcePackCatalogMutationRequest {
    /// Freezes sources and rejects duplicate inputs.
    ResourcePackImportMutation {
        sources = List.copyOf(sources);
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("At least one resource-pack source is required");
        }
        if (new HashSet<>(sources).size() != sources.size()) {
            throw new IllegalArgumentException("Duplicate resource-pack import source");
        }
    }
}
