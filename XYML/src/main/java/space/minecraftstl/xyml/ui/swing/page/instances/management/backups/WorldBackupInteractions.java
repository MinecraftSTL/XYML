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
package space.minecraftstl.xyml.ui.swing.page.instances.management.backups;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/// Native desktop and confirmation boundary for the world-backup Swing page.
///
/// Filesystem-integrating implementations must execute `openDirectory` off the Swing EDT. Keeping
/// these UI boundaries separate lets the page's catalog be tested without showing native dialogs.
@NotNullByDefault
public interface WorldBackupInteractions {
    /// Opens the managed local backup directory using the platform file manager.
    ///
    /// @param directory directory to create if needed and open
    /// @return terminal stage for the asynchronous platform action
    CompletionStage<@Nullable Void> openDirectory(Path directory);

    /// Confirms permanent removal of one selected local backup archive.
    ///
    /// @param owner Swing dialog owner
    /// @param archive selected archive
    /// @return whether the user explicitly accepted permanent deletion
    boolean confirmDelete(Component owner, WorldBackupArchive archive);

    /// Requests a new destination save directory name for a backup restore.
    ///
    /// @param owner Swing dialog owner
    /// @param archive selected archive
    /// @return trimmed requested new save name, or null when cancelled or blank
    @Nullable String requestRestoreDestination(Component owner, WorldBackupArchive archive);

    /// Confirms non-destructive restoration into a user-selected new save directory.
    ///
    /// @param owner Swing dialog owner
    /// @param archive selected archive
    /// @param destinationName requested new save directory name
    /// @return whether the user explicitly accepted restoration
    boolean confirmRestore(Component owner, WorldBackupArchive archive, String destinationName);

    /// Shows a concise failure notification on the EDT.
    ///
    /// @param owner Swing dialog owner
    /// @param title visible failure title
    /// @param detail concise operation failure detail
    void showFailure(Component owner, String title, String detail);
}
