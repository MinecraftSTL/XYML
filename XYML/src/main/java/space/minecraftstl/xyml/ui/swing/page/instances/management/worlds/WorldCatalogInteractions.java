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
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/// Owns Swing dialogs and desktop integration outside the pure world catalog model.
@NotNullByDefault
public interface WorldCatalogInteractions extends AutoCloseable {
    /// Opens a single ZIP archive chooser on the EDT.
    ///
    /// @param owner dialog owner
    /// @param currentDirectory saves directory used as chooser context
    /// @return selected archive, or `null` after cancellation
    @Nullable Path chooseWorldArchive(Component owner, Path currentDirectory);

    /// Prompts for the final install name after Core validates an archive.
    ///
    /// @param owner dialog owner
    /// @param world validated import candidate
    /// @return trimmed target name, or `null` after cancellation or an empty input
    @Nullable String chooseWorldName(Component owner, WorldCatalogImport world);

    /// Confirms permanent deletion of one loaded world on the EDT.
    ///
    /// @param owner dialog owner
    /// @param world selected loaded world
    /// @return whether deletion was explicitly confirmed
    boolean confirmDelete(Component owner, WorldCatalogItem world);

    /// Prompts for a sibling name for a copied world.
    ///
    /// @param owner dialog owner
    /// @param world selected loaded world
    /// @return trimmed target name, or `null` after cancellation
    @Nullable String chooseCopyName(Component owner, WorldCatalogItem world);

    /// Chooses a local ZIP destination and confirms replacement when necessary.
    ///
    /// @param owner dialog owner
    /// @param world selected loaded world
    /// @return normalized ZIP destination, or `null` after cancellation
    @Nullable Path chooseExportArchive(Component owner, WorldCatalogItem world);

    /// Chooses one local PNG candidate for a selected world's icon.
    ///
    /// Implementations without icon dialogs may retain the cancellation default.
    ///
    /// @param owner dialog owner
    /// @param world selected loaded world
    /// @return normalized selected path, or `null` after cancellation
    default @Nullable Path chooseWorldIcon(Component owner, WorldCatalogItem world) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(world, "world");
        return null;
    }

    /// Opens one exact world level-data source in the existing Swing NBT editor.
    ///
    /// Implementations without an editor host may retain the no-op default.
    ///
    /// @param owner editor-window owner
    /// @param levelDataPath exact `level.dat` or `special_level.dat` path
    default void openLevelData(Component owner, Path levelDataPath) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(levelDataPath, "levelDataPath");
    }

    /// Copies one world-detail value through the desktop clipboard boundary.
    ///
    /// Implementations without clipboard integration may retain the no-op default.
    ///
    /// @param owner interaction owner
    /// @param text exact text to copy
    default void copyText(Component owner, String text) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(text, "text");
    }

    /// Chooses a local standalone launch-script destination for one world.
    ///
    /// @param owner dialog owner
    /// @param world selected loaded world
    /// @return normalized script destination, or `null` after cancellation
    @Nullable Path chooseLaunchScriptDestination(Component owner, WorldCatalogItem world);

    /// Reports one successfully generated standalone launch script.
    ///
    /// @param owner dialog owner
    /// @param scriptFile exact generated script path
    void launchScriptSucceeded(Component owner, Path scriptFile);

    /// Schedules creation and opening of one local directory off the EDT.
    ///
    /// @param directory normalized directory to open
    /// @return nullable-void desktop completion
    CompletionStage<@Nullable Void> openDirectory(Path directory);

    /// Reopens one selected world and browses to the requested Chunk Base application off the EDT.
    ///
    /// @param world selected readable world
    /// @param tool requested Chunk Base destination
    /// @return nullable-void browser completion
    CompletionStage<@Nullable Void> openChunkBase(WorldCatalogItem world, ChunkBaseTool tool);

    /// Shows one user-visible operation failure on the EDT.
    ///
    /// @param owner dialog owner
    /// @param title localized dialog title
    /// @param detail concise failure detail
    void showFailure(Component owner, String title, String detail);

    /// Releases any modeless editor window owned by this interaction boundary.
    @Override
    default void close() {
    }
}
