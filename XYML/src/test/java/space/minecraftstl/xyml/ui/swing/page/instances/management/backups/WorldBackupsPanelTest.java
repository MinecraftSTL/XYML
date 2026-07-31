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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies that the Swing page does not index the filesystem until its host activates the tab.
@NotNullByDefault
final class WorldBackupsPanelTest {
    /// Construction leaves the catalog idle, while activation begins exactly one shallow index request.
    @Test
    void indexesOnlyAfterTabActivation() {
        WorldBackupSnapshot snapshot = new WorldBackupSnapshot(
                List.of(new WorldBackupSource(Path.of("source"), "source")),
                List.of());
        ImmediateCatalog catalog = new ImmediateCatalog(snapshot);
        AtomicReference<WorldBackupsPanel> reference = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            WorldBackupsPanel panel = new WorldBackupsPanel(catalog, new NoDialogInteractions());
            reference.set(panel);
            assertEquals(0, catalog.loadCalls.get());
            assertEquals(WorldBackupSnapshot.empty(), panel.displayedSnapshot());

            panel.activate();

            assertEquals(1, catalog.loadCalls.get());
            assertEquals(snapshot, panel.displayedSnapshot());
        });
        EdtDispatcher.executeAndWait(() -> Objects.requireNonNull(reference.get(), "panel").close());
    }

    /// Immediate deterministic catalog used to verify Swing lifecycle sequencing without filesystem work.
    private static final class ImmediateCatalog implements WorldBackupCatalog {
        /// Result returned by every successful catalog action.
        private final WorldBackupSnapshot snapshot;

        /// Number of index requests started by the page.
        private final AtomicInteger loadCalls = new AtomicInteger();

        /// Creates a deterministic catalog returning one predefined snapshot.
        ///
        /// @param snapshot immutable result for every operation
        private ImmediateCatalog(WorldBackupSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        /// Returns a stable test saves path without touching the filesystem.
        ///
        /// @return test saves path
        @Override
        public Path savesDirectory() {
            return Path.of("saves");
        }

        /// Returns a stable test backup path without touching the filesystem.
        ///
        /// @return test backups path
        @Override
        public Path backupsDirectory() {
            return Path.of("backups");
        }

        /// Records and completes one shallow index request immediately.
        ///
        /// @return predefined snapshot
        @Override
        public CompletionStage<WorldBackupSnapshot> load() {
            loadCalls.incrementAndGet();
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Completes a deterministic backup request without filesystem work.
        ///
        /// @param source selected test source
        /// @return predefined snapshot
        @Override
        public CompletionStage<WorldBackupSnapshot> createBackup(WorldBackupSource source) {
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Completes a deterministic deletion request without filesystem work.
        ///
        /// @param archive selected test archive
        /// @return predefined snapshot
        @Override
        public CompletionStage<WorldBackupSnapshot> deleteBackup(WorldBackupArchive archive) {
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Completes a deterministic restore request without filesystem work.
        ///
        /// @param archive selected test archive
        /// @param destinationName selected test destination
        /// @return predefined snapshot
        @Override
        public CompletionStage<WorldBackupSnapshot> restoreBackup(WorldBackupArchive archive, String destinationName) {
            return CompletableFuture.completedFuture(snapshot);
        }
    }

    /// Interaction implementation that fails tests if an activation-only test unexpectedly opens a dialog.
    private static final class NoDialogInteractions implements WorldBackupInteractions {
        /// Completes a no-op open command without accessing a desktop integration.
        ///
        /// @param directory ignored test directory
        /// @return successful no-op completion
        @Override
        public CompletionStage<@Nullable Void> openDirectory(Path directory) {
            return CompletableFuture.completedFuture(null);
        }

        /// Reports that deletion was not confirmed.
        ///
        /// @param owner ignored dialog owner
        /// @param archive ignored selected archive
        /// @return false
        @Override
        public boolean confirmDelete(java.awt.Component owner, WorldBackupArchive archive) {
            return false;
        }

        /// Reports a cancelled restore destination prompt.
        ///
        /// @param owner ignored dialog owner
        /// @param archive ignored selected archive
        /// @return null because no dialog is shown
        @Override
        public @Nullable String requestRestoreDestination(java.awt.Component owner, WorldBackupArchive archive) {
            return null;
        }

        /// Reports that restore was not confirmed.
        ///
        /// @param owner ignored dialog owner
        /// @param archive ignored selected archive
        /// @param destinationName ignored restore destination
        /// @return false
        @Override
        public boolean confirmRestore(java.awt.Component owner, WorldBackupArchive archive, String destinationName) {
            return false;
        }

        /// Fails an activation-only test if a failure dialog would have been requested.
        ///
        /// @param owner ignored dialog owner
        /// @param title ignored dialog title
        /// @param detail ignored dialog detail
        @Override
        public void showFailure(java.awt.Component owner, String title, String detail) {
            throw new AssertionError("Unexpected dialog: " + title + ": " + detail);
        }
    }
}
