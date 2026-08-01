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
package space.minecraftstl.xyml.ui.swing.page.instances.management.datapacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.datapack.DataPack;

import java.awt.Component;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;

/// Owns native chooser, confirmation, desktop, and error-dialog work outside the data-pack panel.
///
/// Production implementations keep native Swing dialogs on the EDT and schedule desktop operations
/// elsewhere. Test implementations can record calls without requiring a graphical desktop.
@NotNullByDefault
public interface DataPackManagementInteractions {
    /// Opens a ZIP-only chooser for a local data-pack archive.
    ///
    /// @param owner dialog owner
    /// @param initialDirectory initial local directory for the chooser
    /// @return selected ZIP path, or `null` when the chooser is cancelled
    @Nullable Path chooseDataPackArchive(Component owner, Path initialDirectory);

    /// Confirms permanent deletion of all selected data packs in one destructive action.
    ///
    /// @param owner dialog owner
    /// @param dataPacks selected durable data-pack entries
    /// @return whether the user explicitly accepted deletion
    boolean confirmDelete(Component owner, @Unmodifiable List<DataPack.Pack> dataPacks);

    /// Schedules creation and platform opening of one local directory outside the EDT.
    ///
    /// @param directory directory to reveal
    /// @return nullable-void desktop-operation completion
    CompletionStage<@Nullable Void> openDirectory(Path directory);

    /// Shows one concise error message on the EDT.
    ///
    /// @param owner dialog owner
    /// @param title visible dialog title
    /// @param detail concise failure detail
    void showFailure(Component owner, String title, String detail);
}
