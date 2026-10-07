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

import java.nio.file.Path;
import java.util.Objects;

/// Reorders one enabled resource pack within the persisted Minecraft priority list.
///
/// @param path normalized stable current-index path
/// @param targetIndex final zero-based index in display order, where the first entry has highest priority
@NotNullByDefault
record ResourcePackReorderMutation(
        Path path,
        int targetIndex) implements ResourcePackCatalogMutationRequest {
    /// Validates one stable target path and non-negative display index.
    ResourcePackReorderMutation {
        Objects.requireNonNull(path, "path");
        if (targetIndex < 0) {
            throw new IllegalArgumentException("targetIndex must not be negative");
        }
    }
}
