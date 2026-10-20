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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.FileSaver;
import space.minecraftstl.xyml.util.io.DeletionMode;

import java.io.IOException;

/// Executes instance lifecycle mutations without exposing a repository implementation to Swing controls.
///
/// The task factories schedule rename, duplication, deletion, repository refresh, and selection reconciliation
/// away from their caller. Direct mutation methods remain blocking compatibility entry points and must not run on
/// a UI thread. `reconcileSelection` updates launcher observable state on the Swing event-dispatch thread.
@NotNullByDefault
public interface InstanceLifecycleService {
    /// Returns whether one candidate can be used as a filesystem-safe destination instance identifier.
    ///
    /// This inexpensive syntactic check does not replace conflict checking performed by a mutation.
    ///
    /// @param destinationId candidate destination identifier
    /// @return whether the candidate is valid before repository I/O
    boolean isValidDestinationId(String destinationId);

    /// Renames one existing source instance and refreshes the repository after the disk mutation.
    ///
    /// @param sourceId stable existing source identifier
    /// @param destinationId validated target identifier
    /// @throws IOException when the target conflicts, the source cannot be renamed, or refresh fails
    void rename(GameInstanceID sourceId, GameInstanceID destinationId) throws IOException;

    /// Duplicates one existing source instance and refreshes the repository after copying files.
    ///
    /// @param sourceId stable existing source identifier
    /// @param destinationId validated target identifier
    /// @param copySaves whether source saves should be copied
    /// @throws IOException when the target conflicts, copying fails, or refresh fails
    void duplicate(GameInstanceID sourceId, GameInstanceID destinationId, boolean copySaves) throws IOException;

    /// Deletes one existing source instance and refreshes the repository after deletion.
    ///
    /// @param sourceId stable existing source identifier
    /// @throws IOException when the instance cannot be removed or refresh fails
    void delete(GameInstanceID sourceId) throws IOException;

    /// Creates an unstarted task for a complete rename lifecycle.
    ///
    /// Implementations with repository knowledge should override this method with precise resources. The conservative
    /// default keeps test and alternate implementations safe without making an empty declaration mean lock-free.
    ///
    /// @param sourceId stable existing source identifier
    /// @param destinationId validated target identifier
    /// @return unstarted rename task
    default Task<@Nullable Void> renameTask(GameInstanceID sourceId, GameInstanceID destinationId) {
        return Task.runAsync("Rename game instance", Schedulers.io(), () -> {
            rename(sourceId, destinationId);
            EdtDispatcher.executeAndWait(() -> reconcileSelection(destinationId));
            FileSaver.waitForAllSaves();
        });
    }

    /// Creates an unstarted task for a complete duplication lifecycle.
    ///
    /// Implementations with repository knowledge should override this method with precise resources.
    ///
    /// @param sourceId stable existing source identifier
    /// @param destinationId validated target identifier
    /// @param copySaves whether source saves should be copied
    /// @return unstarted duplication task
    default Task<@Nullable Void> duplicateTask(
            GameInstanceID sourceId,
            GameInstanceID destinationId,
            boolean copySaves) {
        return Task.runAsync(
                "Duplicate game instance",
                Schedulers.io(),
                () -> {
                    duplicate(sourceId, destinationId, copySaves);
                    EdtDispatcher.executeAndWait(() -> reconcileSelection(destinationId));
                    FileSaver.waitForAllSaves();
                });
    }

    /// Creates an unstarted task for a complete deletion lifecycle.
    ///
    /// Implementations with repository knowledge should override this method with precise resources.
    ///
    /// @param sourceId stable existing source identifier
    /// @return unstarted deletion task
    default Task<@Nullable Void> deleteTask(GameInstanceID sourceId) {
        return Task.runAsync("Delete game instance", Schedulers.io(), () -> {
            delete(sourceId);
            EdtDispatcher.executeAndWait(() -> reconcileSelection(null));
            FileSaver.waitForAllSaves();
        });
    }

    /// Creates an unstarted task for a complete deletion lifecycle using the requested deletion mode.
    ///
    /// @param sourceId stable existing source identifier
    /// @param mode recycle-bin-first or permanent deletion behavior
    /// @return unstarted deletion task
    default Task<@Nullable Void> deleteTask(GameInstanceID sourceId, DeletionMode mode) {
        return Task.runAsync("Delete game instance", Schedulers.io(), () -> {
            if (mode != DeletionMode.PERMANENT) {
                throw new UnsupportedOperationException("This lifecycle service does not support recycle-bin deletion");
            }
            delete(sourceId);
            EdtDispatcher.executeAndWait(() -> reconcileSelection(null));
            FileSaver.waitForAllSaves();
        });
    }

    /// Updates the persisted selected instance after a successful background mutation.
    ///
    /// A non-null preferred identifier is selected when it survived refresh. Otherwise the repository
    /// chooses its first remaining instance or clears the selection when empty.
    ///
    /// @param preferredId preferred selection after rename or duplication, or `null` after deletion
    void reconcileSelection(@Nullable GameInstanceID preferredId);
}
