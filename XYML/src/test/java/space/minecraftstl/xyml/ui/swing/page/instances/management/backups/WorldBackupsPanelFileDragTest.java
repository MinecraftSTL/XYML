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
import space.minecraftstl.xyml.ui.swing.choice.CatalogLayoutAssertions;
import space.minecraftstl.xyml.ui.swing.choice.FileDragAssertions;
import space.minecraftstl.xyml.ui.swing.choice.RichChoiceListCellRenderer;
import space.minecraftstl.xyml.ui.swing.choice.RichValueListCellRenderer;
import space.minecraftstl.xyml.util.io.DeletionMode;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListSelectionModel;
import javax.swing.TransferHandler;
import java.awt.Component;
import java.awt.datatransfer.Transferable;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies large non-sortable backup rows, copy-only ZIP export, and existing archive commands.
@NotNullByDefault
public final class WorldBackupsPanelFileDragTest {
    /// The large renderer displays indexed metadata without opening archives and has no priority handle.
    @Test
    public void rendersLargeBackupRowsAndExportsZipWithoutSorting() {
        EdtDispatcher.executeAndWait(() -> {
            RecordingCatalog catalog = new RecordingCatalog(snapshot());
            try (WorldBackupsPanel panel = new WorldBackupsPanel(catalog, new AcceptingInteractions())) {
                panel.activate();
                JList<?> list = archiveList(panel);
                list.setSelectedIndex(0);
                WorldBackupArchive first = catalog.snapshot.archives().get(0);
                assertInstanceOf(RichValueListCellRenderer.class, list.getCellRenderer());
                assertEquals(RichChoiceListCellRenderer.ROW_HEIGHT, list.getFixedCellHeight());
                assertEquals(ListSelectionModel.SINGLE_SELECTION, list.getSelectionMode());
                JComponent row = renderRow(list);
                JLabel primary = CatalogLayoutAssertions.requireNamed(row, "richChoiceListPrimary", JLabel.class);
                JLabel secondary = CatalogLayoutAssertions.requireNamed(row, "richChoiceListSecondary", JLabel.class);
                JLabel icon = CatalogLayoutAssertions.requireNamed(row, "richChoiceListIcon", JLabel.class);
                JLabel badge = CatalogLayoutAssertions.requireNamed(row, "richChoiceListBadge", JLabel.class);
                assertEquals(first.fileName(), primary.getText());
                assertTrue(secondary.getText().contains("KiB"));
                assertEquals(CatalogLayoutAssertions.requireNamed(panel, "worldBackupsDetail", JLabel.class).getText(),
                        secondary.getText());
                assertNotNull(icon.getIcon());
                assertEquals(40, icon.getIcon().getIconWidth());
                assertNull(badge.getIcon());
                assertEquals(first.archive().toString(), row.getToolTipText());
                FileDragAssertions.assertFiles(list, List.of(first.archive()));
                Transferable own = FileDragAssertions.capture(list);
                assertFalse(list.getTransferHandler().importData(new TransferHandler.TransferSupport(list, own)));
                assertEquals(TransferHandler.COPY, list.getTransferHandler().getSourceActions(list));
                assertEquals(javax.swing.DropMode.USE_SELECTION, list.getDropMode());
                assertSame(first, list.getModel().getElementAt(0));
                assertSame(catalog.snapshot.archives().get(1), list.getModel().getElementAt(1));
                assertNull(catalog.created);
                assertNull(catalog.deleted);
                assertNull(catalog.restored);
            }
        });
    }

    /// Creating, restoring, and deleting continue to delegate exact archive identities and preserve valid selection.
    @Test
    public void preservesArchiveCommandsAndSelectionAfterLargeListChange() {
        EdtDispatcher.executeAndWait(() -> {
            RecordingCatalog catalog = new RecordingCatalog(snapshot());
            try (WorldBackupsPanel panel = new WorldBackupsPanel(catalog, new AcceptingInteractions())) {
                panel.activate();
                JList<?> list = archiveList(panel);
                list.setSelectedIndex(0);
                WorldBackupArchive first = catalog.snapshot.archives().get(0);
                WorldBackupArchive second = catalog.snapshot.archives().get(1);
                CatalogLayoutAssertions.requireNamed(panel, "worldBackupsRestore", JButton.class).doClick();
                assertSame(first, catalog.restored);
                assertEquals("restored-copy", catalog.restoredName);
                FileDragAssertions.assertFiles(list, List.of(first.archive()));
                CatalogLayoutAssertions.requireNamed(panel, "worldBackupsCreate", JButton.class).doClick();
                assertEquals(catalog.snapshot.sources().get(0), catalog.created);
                FileDragAssertions.assertFiles(list, List.of(first.archive()));
                CatalogLayoutAssertions.requireNamed(panel, "worldBackupsDelete", JButton.class).doClick();
                assertSame(first, catalog.deleted);
                assertEquals(1, list.getModel().getSize());
                assertNull(list.getSelectedValue());
                list.setSelectedIndex(0);
                FileDragAssertions.assertFiles(list, List.of(second.archive()));
                CatalogLayoutAssertions.requireNamed(panel, "worldBackupsRefresh", JButton.class).doClick();
                FileDragAssertions.assertFiles(list, List.of(second.archive()));
            }
        });
    }

    /// Pending refresh invalidates old drags immediately, then re-enables copying after its coherent index arrives.
    @Test
    public void rejectsPendingRefreshAndDetachesOnlyOnFinalClose() {
        EdtDispatcher.executeAndWait(() -> {
            RecordingCatalog catalog = new RecordingCatalog(snapshot());
            WorldBackupsPanel panel = new WorldBackupsPanel(catalog, new AcceptingInteractions());
            try {
                panel.activate();
                JList<?> list = archiveList(panel);
                list.setSelectedIndex(0);
                FileDragAssertions.capture(list);
                CompletableFuture<WorldBackupSnapshot> pending = new CompletableFuture<>();
                catalog.nextLoad = pending;
                CatalogLayoutAssertions.requireNamed(panel, "worldBackupsRefresh", JButton.class).doClick();
                FileDragAssertions.assertPendingUnavailable(list);
                FileDragAssertions.assertCannotStart(list);
                pending.complete(catalog.snapshot);
                FileDragAssertions.assertFiles(list, List.of(catalog.snapshot.archives().get(0).archive()));
                panel.close();
                assertFalse(java.util.Arrays.stream(list.getMouseListeners()).anyMatch(
                        space.minecraftstl.xyml.ui.swing.choice.FileListDragSupport.class::isInstance));
            } finally {
                panel.close();
            }
        });
    }

    /// Creates a shallow newest-first index containing only fixture paths and metadata.
    ///
    /// @return deterministic archives in the catalog's existing display order
    private static WorldBackupSnapshot snapshot() {
        return new WorldBackupSnapshot(List.of(new WorldBackupSource(Path.of("saves", "world"), "world")), List.of(
                new WorldBackupArchive(Path.of("backups", "newest.zip"), "newest.zip", 2048L,
                        Instant.parse("2026-10-26T00:00:00Z")),
                new WorldBackupArchive(Path.of("backups", "older.zip"), "older.zip", 512L,
                        Instant.parse("2026-10-25T00:00:00Z"))));
    }

    /// Obtains and sizes the actual archive list without replacing its normal value model.
    ///
    /// @param panel initialized backup page
    /// @return page-owned archive list
    private static JList<?> archiveList(WorldBackupsPanel panel) {
        JList<?> list = CatalogLayoutAssertions.requireNamed(panel, "worldBackupsList", JList.class);
        list.setSize(600, 240);
        return list;
    }

    /// Renders a selected ordinary value through its actual configured renderer without unchecked casts.
    ///
    /// @param list selected page-owned list
    /// @param <T> actual row value type
    /// @return mounted renderer surface
    private static <T> JComponent renderRow(JList<T> list) {
        T value = Objects.requireNonNull(list.getSelectedValue());
        return assertInstanceOf(JComponent.class, list.getCellRenderer().getListCellRendererComponent(
                list, value, list.getSelectedIndex(), true, false));
    }

    /// In-memory asynchronous catalog recording archive commands without ever opening ZIP files.
    @NotNullByDefault
    private static final class RecordingCatalog implements WorldBackupCatalog {
        /// Current immutable index returned after each command.
        private WorldBackupSnapshot snapshot;

        /// Optional externally completed index request.
        private @Nullable CompletableFuture<WorldBackupSnapshot> nextLoad;

        /// Source passed to the latest create command.
        private @Nullable WorldBackupSource created;

        /// Exact archive passed to the latest delete command.
        private @Nullable WorldBackupArchive deleted;

        /// Exact archive passed to the latest restore command.
        private @Nullable WorldBackupArchive restored;

        /// Requested restore destination, never an existing save directory.
        private @Nullable String restoredName;

        /// Creates one isolated catalog.
        ///
        /// @param snapshot initial shallow index
        private RecordingCatalog(WorldBackupSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        /// Returns the fixture save directory without filesystem access.
        @Override
        public Path savesDirectory() {
            return Path.of("saves");
        }

        /// Returns the fixture archive directory without filesystem access.
        @Override
        public Path backupsDirectory() {
            return Path.of("backups");
        }

        /// Returns either a controlled pending request or the current shallow index.
        @Override
        public CompletionStage<WorldBackupSnapshot> load() {
            @Nullable CompletableFuture<WorldBackupSnapshot> pending = nextLoad;
            nextLoad = null;
            return pending == null ? CompletableFuture.completedFuture(snapshot) : pending;
        }

        /// Records the selected source without constructing an archive.
        @Override
        public CompletionStage<WorldBackupSnapshot> createBackup(WorldBackupSource source) {
            created = source;
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Removes only the selected fixture entry from the shallow index.
        @Override
        public CompletionStage<WorldBackupSnapshot> deleteBackup(WorldBackupArchive archive) {
            deleted = archive;
            snapshot = new WorldBackupSnapshot(snapshot.sources(), snapshot.archives().stream()
                    .filter(item -> !item.archive().equals(archive.archive())).toList());
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Records the exact restore identity without unpacking anything.
        @Override
        public CompletionStage<WorldBackupSnapshot> restoreBackup(WorldBackupArchive archive, String destinationName) {
            restored = archive;
            restoredName = destinationName;
            return CompletableFuture.completedFuture(snapshot);
        }
    }

    /// Deterministic confirmations proving existing backup commands remain connected.
    @NotNullByDefault
    private static final class AcceptingInteractions implements WorldBackupInteractions {
        /// Completes a no-op open command without desktop access.
        @Override
        public CompletionStage<@Nullable Void> openDirectory(Path directory) {
            return CompletableFuture.completedFuture(null);
        }

        /// Accepts deletion only inside the in-memory fixture.
        @Override
        public boolean confirmDelete(Component owner, WorldBackupArchive archive) {
            return true;
        }

        /// Avoids invoking platform recycle-bin detection during UI regression tests.
        @Override
        public DeletionMode chooseDeleteMode(Component owner, WorldBackupArchive archive) {
            return DeletionMode.PERMANENT;
        }

        /// Supplies a new restore directory name without showing a dialog.
        @Override
        public String requestRestoreDestination(Component owner, WorldBackupArchive archive) {
            return "restored-copy";
        }

        /// Accepts only the deterministic restore destination.
        @Override
        public boolean confirmRestore(Component owner, WorldBackupArchive archive, String destinationName) {
            return destinationName.equals("restored-copy");
        }

        /// Fails any unexpected interaction error instead of opening a dialog.
        @Override
        public void showFailure(Component owner, String title, String detail) {
            throw new AssertionError(title + ": " + detail);
        }
    }
}
