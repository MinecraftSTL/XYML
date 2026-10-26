// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.backups;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.util.io.DeletionMode;

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

    /// Deletes one selected archive using the selected recycle-bin or permanent mode.
    ///
    /// @param archive backup archive selected by the user
    /// @param mode selected deletion behavior
    /// @return terminal immutable shallow snapshot after deletion
    default CompletionStage<WorldBackupSnapshot> deleteBackup(
            WorldBackupArchive archive,
            DeletionMode mode) {
        return deleteBackup(archive);
    }

    /// Restores one selected archive into a new directory below the managed saves directory.
    ///
    /// Existing saves are never overwritten: callers must provide a new single-directory name.
    ///
    /// @param archive backup archive selected by the user
    /// @param destinationName new world directory and stored level name
    /// @return terminal immutable shallow snapshot after successful restore
    CompletionStage<WorldBackupSnapshot> restoreBackup(WorldBackupArchive archive, String destinationName);
}
