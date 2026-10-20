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
import space.minecraftstl.xyml.util.io.DeletionMode;

import java.nio.file.Path;
import java.util.Objects;

/// Single-pack persistent disable-then-delete request.
///
/// @param path normalized stable current-index path
/// @param mode selected deletion behavior
@NotNullByDefault
record ResourcePackDeleteMutation(Path path, DeletionMode mode) implements ResourcePackCatalogMutationRequest {
    /// Creates a permanent-delete request for compatibility callers.
    ///
    /// @param path normalized stable current-index path
    ResourcePackDeleteMutation(Path path) {
        this(path, DeletionMode.PERMANENT);
    }

    /// Validates the stable target path and mode.
    ResourcePackDeleteMutation {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");
    }
}
