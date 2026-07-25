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
package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.awt.Component;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;

/// Owns Mod interactions that cross Swing dialog and platform desktop boundaries.
@NotNullByDefault
public interface ModCatalogInteractions {
    /// Opens a multi-selection Mod archive chooser on the EDT.
    ///
    /// @param owner dialog owner
    /// @param currentDirectory initial managed Mod directory
    /// @return immutable selected paths, or empty after cancellation
    @Unmodifiable List<Path> chooseImportFiles(Component owner, Path currentDirectory);

    /// Confirms permanent deletion on the EDT.
    ///
    /// @param owner dialog owner
    /// @param target exact loaded target
    /// @return whether deletion was explicitly confirmed
    boolean confirmDelete(Component owner, ModCatalogItem target);

    /// Schedules revealing one exact current Mod file.
    ///
    /// @param target normalized current file path
    /// @return asynchronous desktop completion
    CompletionStage<@Nullable Void> reveal(Path target);

    /// Schedules ensuring and opening the managed Mod directory.
    ///
    /// @param directory normalized managed directory
    /// @return asynchronous file and desktop completion
    CompletionStage<@Nullable Void> openDirectory(Path directory);

    /// Shows one failure dialog on the EDT.
    ///
    /// @param owner dialog owner
    /// @param title localized title
    /// @param detail failure detail
    void showFailure(Component owner, String title, String detail);
}
