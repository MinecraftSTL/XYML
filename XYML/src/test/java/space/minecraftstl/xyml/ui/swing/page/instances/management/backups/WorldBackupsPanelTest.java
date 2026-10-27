// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.backups;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.CatalogLayoutAssertions;
import space.minecraftstl.xyml.ui.swing.dialog.DeletionFallbackDecision;
import space.minecraftstl.xyml.util.io.DeletionMode;
import space.minecraftstl.xyml.util.io.TrashMoveException;

import javax.swing.JButton;
import javax.swing.JList;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

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
            assertEquals(i18n("world.backup.empty"), panel.statusText());
        });
        EdtDispatcher.executeAndWait(() -> Objects.requireNonNull(reference.get(), "panel").close());
    }

    /// Repeated recycle-bin failures keep the same captured archive for all three choices.
    @Test
    public void retriesSameBackupArchiveUntilCancelledOrDeleted() {
        for (DeletionFallbackDecision decision : DeletionFallbackDecision.values()) {
            WorldBackupArchive archive = new WorldBackupArchive(Path.of("missing-backup.zip"), "backup.zip", 0L,
                    Instant.EPOCH);
            ImmediateCatalog catalog = new ImmediateCatalog(new WorldBackupSnapshot(List.of(), List.of(archive)));
            catalog.deletionFailures.add(new TrashMoveException(List.of(archive.archive())));
            catalog.deletionFailures.add(new TrashMoveException(List.of(archive.archive())));
            NoDialogInteractions interactions = new NoDialogInteractions();
            interactions.decisions.add(DeletionFallbackDecision.RETRY_RECYCLE_BIN);
            interactions.decisions.add(decision);
            AtomicReference<@Nullable WorldBackupsPanel> reference = new AtomicReference<>();
            EdtDispatcher.executeAndWait(() -> {
                WorldBackupsPanel panel = new WorldBackupsPanel(catalog, interactions);
                reference.set(panel);
                panel.activate();
                CatalogLayoutAssertions.requireNamed(panel, "worldBackupsList", JList.class).setSelectedIndex(0);
                CatalogLayoutAssertions.requireNamed(panel, "worldBackupsDelete", JButton.class).doClick();
            });
            for (int turn = 0; turn < 8; turn++) {
                EdtDispatcher.executeAndWait(() -> { });
            }
            EdtDispatcher.executeAndWait(() -> {
                try {
                    List<DeletionMode> expected = new ArrayList<>(List.of(
                            DeletionMode.RECYCLE_BIN_FIRST, DeletionMode.RECYCLE_BIN_FIRST));
                    if (decision != DeletionFallbackDecision.CANCEL) {
                        expected.add(decision == DeletionFallbackDecision.RETRY_RECYCLE_BIN
                                ? DeletionMode.RECYCLE_BIN_FIRST : DeletionMode.PERMANENT);
                    }
                    assertEquals(expected, catalog.deletionModes);
                    assertEquals(java.util.Collections.nCopies(expected.size(), archive), catalog.deletionTargets);
                    assertEquals(2, interactions.fallbackCount);
                } finally {
                    Objects.requireNonNull(reference.get()).close();
                }
            });
        }
    }

    /// Immediate deterministic catalog used to verify Swing lifecycle sequencing without filesystem work.
    @NotNullByDefault
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

        /// Ordered failures consumed by deletion attempts.
        private final List<Throwable> deletionFailures = new ArrayList<>();

        /// Exact modes requested in order.
        private final List<DeletionMode> deletionModes = new ArrayList<>();

        /// Captured archive identity for every request.
        private final List<WorldBackupArchive> deletionTargets = new ArrayList<>();

        /// Records an attempt and returns its controlled asynchronous result.
        @Override
        public CompletionStage<WorldBackupSnapshot> deleteBackup(WorldBackupArchive archive, DeletionMode mode) {
            deletionTargets.add(archive);
            deletionModes.add(mode);
            return deletionFailures.isEmpty() ? CompletableFuture.completedFuture(snapshot)
                    : CompletableFuture.failedFuture(deletionFailures.remove(0));
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
    @NotNullByDefault
    private static final class NoDialogInteractions implements WorldBackupInteractions {
        /// Ordered fallback choices for deletion scenarios.
        private final List<DeletionFallbackDecision> decisions = new ArrayList<>();

        /// Number of fallback decisions requested.
        private int fallbackCount;

        /// Starts recovery scenarios with recycle-bin-first deletion.
        @Override
        public DeletionMode chooseDeleteMode(java.awt.Component owner, WorldBackupArchive archive) {
            return DeletionMode.RECYCLE_BIN_FIRST;
        }

        /// Returns the next explicit recovery choice for the captured archive.
        @Override
        public DeletionFallbackDecision choosePermanentFallback(java.awt.Component owner, WorldBackupArchive archive) {
            fallbackCount++;
            return decisions.remove(0);
        }

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
