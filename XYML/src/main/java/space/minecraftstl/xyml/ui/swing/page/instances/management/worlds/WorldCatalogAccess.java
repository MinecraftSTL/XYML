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
package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/// Blocking Core and filesystem boundary for the lazily materialized world catalog.
///
/// Implementations must perform index creation from directory names and paths only. `World`
/// construction belongs exclusively to `loadItem`, which receives the narrow viewport slice.
@NotNullByDefault
interface WorldCatalogAccess {
    /// Resolves the normalized game version belonging to the managed instance.
    ///
    /// Sources without an owning instance may retain the empty default.
    ///
    /// @return current instance game version, or empty when it cannot be resolved
    default Optional<String> instanceGameVersion() {
        return Optional.empty();
    }

    /// Resolves the managed saves directory without enumerating it.
    ///
    /// @return normalized saves directory
    Path savesDirectory();

    /// Produces a stable shallow index of direct-child world directories.
    ///
    /// @param cancellation cooperative index cancellation signal
    /// @return immutable ordered direct-child directory paths
    /// @throws IOException when the saves directory cannot be listed
    @Unmodifiable List<Path> indexWorldDirectories(LoadCancellation cancellation) throws IOException;

    /// Materializes metadata for one direct-child world directory.
    ///
    /// Parse failures should become unreadable row values rather than failing the complete list.
    ///
    /// @param directory exact normalized directory from the current shallow index
    /// @param cancellation cooperative range cancellation signal
    /// @return one non-null viewport row
    WorldCatalogItem loadItem(Path directory, LoadCancellation cancellation);

    /// Validates a chosen local archive and obtains its default installation name.
    ///
    /// @param archive selected local archive
    /// @param cancellation cooperative operation cancellation signal
    /// @return import candidate derived through the Core World API
    /// @throws IOException when the archive is not a readable world
    WorldCatalogImport inspectImport(Path archive, LoadCancellation cancellation) throws IOException;

    /// Installs one previously validated archive under the user-selected target name.
    ///
    /// @param world import candidate
    /// @param targetName non-blank target directory and stored level name
    /// @param cancellation cooperative operation cancellation signal
    /// @throws IOException when Core cannot install the archive
    void install(WorldCatalogImport world, String targetName, LoadCancellation cancellation) throws IOException;

    /// Deletes one validated, unlocked current world through the Core World API.
    ///
    /// @param world current loaded world row
    /// @param cancellation cooperative operation cancellation signal
    /// @throws IOException when Core cannot delete the world
    void delete(WorldCatalogItem world, LoadCancellation cancellation) throws IOException;

    /// Copies one validated, unlocked current world beside its source.
    ///
    /// Implementations that do not support mutation may retain this default failure.
    ///
    /// @param world current loaded world row
    /// @param targetName non-blank sibling directory and stored level name
    /// @param cancellation cooperative operation cancellation signal
    /// @throws IOException when Core cannot copy the world
    default void copy(
            WorldCatalogItem world,
            String targetName,
            LoadCancellation cancellation) throws IOException {
        throw new UnsupportedOperationException("World copying is unavailable");
    }

    /// Exports one validated, unlocked current world to an atomic ZIP destination.
    ///
    /// Implementations that do not support export may retain this default failure.
    ///
    /// @param world current loaded world row
    /// @param archive normalized ZIP destination
    /// @param cancellation cooperative operation cancellation signal
    /// @throws IOException when Core cannot export the world
    default void export(
            WorldCatalogItem world,
            Path archive,
            LoadCancellation cancellation) throws IOException {
        throw new UnsupportedOperationException("World export is unavailable");
    }
}
