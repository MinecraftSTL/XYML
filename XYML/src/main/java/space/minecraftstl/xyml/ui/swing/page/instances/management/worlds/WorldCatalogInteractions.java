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
public interface WorldCatalogInteractions {
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
    /// The default preserves source compatibility for non-production test interactions.
    ///
    /// @param owner dialog owner
    /// @param world selected loaded world
    /// @return trimmed target name, or `null` after cancellation
    default @Nullable String chooseCopyName(Component owner, WorldCatalogItem world) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(world, "world");
        return null;
    }

    /// Chooses a local ZIP destination and confirms replacement when necessary.
    ///
    /// The default preserves source compatibility for non-production test interactions.
    ///
    /// @param owner dialog owner
    /// @param world selected loaded world
    /// @return normalized ZIP destination, or `null` after cancellation
    default @Nullable Path chooseExportArchive(Component owner, WorldCatalogItem world) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(world, "world");
        return null;
    }

    /// Chooses a local standalone launch-script destination for one world.
    ///
    /// The default preserves source compatibility for test and download-page interactions without launch commands.
    ///
    /// @param owner dialog owner
    /// @param world selected loaded world
    /// @return normalized script destination, or `null` after cancellation
    default @Nullable Path chooseLaunchScriptDestination(Component owner, WorldCatalogItem world) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(world, "world");
        return null;
    }

    /// Reports one successfully generated standalone launch script.
    ///
    /// The default intentionally does nothing for non-production test interactions.
    ///
    /// @param owner dialog owner
    /// @param scriptFile exact generated script path
    default void launchScriptSucceeded(Component owner, Path scriptFile) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(scriptFile, "scriptFile");
    }

    /// Schedules creation and opening of one local directory off the EDT.
    ///
    /// @param directory normalized directory to open
    /// @return nullable-void desktop completion
    CompletionStage<@Nullable Void> openDirectory(Path directory);

    /// Shows one user-visible operation failure on the EDT.
    ///
    /// @param owner dialog owner
    /// @param title localized dialog title
    /// @param detail concise failure detail
    void showFailure(Component owner, String title, String detail);
}
