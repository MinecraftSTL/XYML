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

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/// Asynchronous local-world backup operations owned by a Swing page.
///
/// Every operation returns its terminal shallow index so a panel can render one coherent state only
/// after the background filesystem work has completed.
@NotNullByDefault
public interface WorldBackupCatalog {
    /// Returns the managed instance's saves directory without enumerating it.
    ///
    /// @return normalised saves directory
    Path savesDirectory();

    /// Returns the managed instance's local backup directory without enumerating it.
    ///
    /// @return normalised backups directory
    Path backupsDirectory();

    /// Starts a lightweight scan of direct save directories and backup ZIP files.
    ///
    /// @return terminal immutable shallow snapshot
    CompletionStage<WorldBackupSnapshot> load();

    /// Exports one selected source world to a new local ZIP archive.
    ///
    /// @param source shallow source selected by the user
    /// @return terminal immutable shallow snapshot after successful creation
    CompletionStage<WorldBackupSnapshot> createBackup(WorldBackupSource source);

    /// Deletes one selected local backup archive permanently.
    ///
    /// @param archive backup archive selected by the user
    /// @return terminal immutable shallow snapshot after successful deletion
    CompletionStage<WorldBackupSnapshot> deleteBackup(WorldBackupArchive archive);

    /// Restores one selected archive into a new directory below the managed saves directory.
    ///
    /// Existing saves are never overwritten: callers must provide a new single-directory name.
    ///
    /// @param archive backup archive selected by the user
    /// @param destinationName new world directory and stored level name
    /// @return terminal immutable shallow snapshot after successful restore
    CompletionStage<WorldBackupSnapshot> restoreBackup(WorldBackupArchive archive, String destinationName);
}
