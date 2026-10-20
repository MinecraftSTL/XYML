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
package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.io.DeletionMode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/// Blocking local shader-pack catalog access used by the asynchronous model.
@NotNullByDefault
public interface ShaderPackCatalogAccess {
    /// Enumerates supported direct-child shader-pack paths in stable file-name order.
    ///
    /// @return immutable shallow path index
    /// @throws IOException when the managed directory cannot be enumerated
    @Unmodifiable List<Path> loadIndex() throws IOException;

    /// Loads metadata and enabled state for exactly the supplied paths.
    ///
    /// @param paths normalized direct-child paths
    /// @return one row per path in identical order
    /// @throws IOException when a shared configuration cannot be read
    @Unmodifiable List<ShaderPackCatalogItem> loadItems(@Unmodifiable List<Path> paths) throws IOException;

    /// Detects shader runtimes available to this instance.
    ///
    /// @return immutable available backend set
    /// @throws IOException when installation evidence cannot be inspected
    @Unmodifiable Set<ShaderPackBackend> detectAvailableBackends() throws IOException;

    /// Imports every source without overwriting an existing target.
    ///
    /// @param sources normalized source ZIP archives or directories
    /// @throws IOException when validation, copying, publication, or cleanup fails
    void importShaderPacks(@Unmodifiable List<Path> sources) throws IOException;

    /// Updates one pack's selection in the requested backends.
    ///
    /// @param path normalized current direct-child path
    /// @param backends non-empty backend set to update
    /// @param enabled desired persistent state
    /// @throws IOException when configuration reading, writing, or rollback fails
    void setEnabled(Path path, @Unmodifiable Set<ShaderPackBackend> backends, boolean enabled) throws IOException;

    /// Deletes one pack after clearing its backend references.
    ///
    /// @param path normalized current direct-child path
    /// @param mode exact recycle-bin or permanent deletion mode
    /// @throws IOException when configuration cleanup, deletion, or rollback fails
    void delete(Path path, DeletionMode mode) throws IOException;
}
