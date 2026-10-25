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
package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.shell.ShellFileDropHandler;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.TransferHandler;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that ordinary value models share the file-only COPY lifecycle without changing their row types.
@NotNullByDefault
public final class ValueListFileDragSupportTest {
    /// Isolated files proving that export does not move, unpack, or modify sources.
    @TempDir
    private Path temporaryDirectory;

    /// Ordinary rows keep their exact model and multi-selection while exporting only immutable local file references.
    @Test
    public void exportsOrdinaryFilesAndDirectoriesInVisibleOrderWithoutChangingModel() throws Exception {
        Path archive = Files.writeString(temporaryDirectory.resolve("pack one.zip"), "archive fixture");
        Path directory = Files.createDirectory(temporaryDirectory.resolve("目录 two"));
        EdtDispatcher.executeAndWait(() -> {
            try (Fixture fixture = fixture(List.of(archive, directory))) {
                fixture.list().setSelectedIndices(new int[] {1, 0});
                assertSame(fixture.model(), fixture.list().getModel());
                assertSame(archive, fixture.model().getElementAt(0));
                FileDragAssertions.assertFiles(fixture.list(), List.of(archive, directory));
                Clipboard clipboard = new Clipboard("ordinary files");
                fixture.list().getTransferHandler().exportToClipboard(fixture.list(), clipboard, TransferHandler.COPY);
                Transferable transfer = Objects.requireNonNull(clipboard.getContents(null));
                assertFalse(transfer.isDataFlavorSupported(DataFlavor.stringFlavor));
                assertEquals(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION, fixture.list().getSelectionMode());
                assertNull(fixture.dragging().pendingTransfer());
            }
        });
        assertEquals("archive fixture", Files.readString(archive));
        assertTrue(Files.isDirectory(directory));
    }

    /// Snapshot changes, row replacement, and a pending operation invalidate the original gesture.
    @Test
    public void rejectsStaleOrdinaryRowsAndPendingWrites() {
        EdtDispatcher.executeAndWait(() -> {
            try (Fixture fixture = fixture(List.of(Path.of("one.zip"), Path.of("two.zip")))) {
                FileDragAssertions.capture(fixture.list());
                fixture.revision().incrementAndGet();
                FileDragAssertions.assertPendingUnavailable(fixture.list());
                FileDragAssertions.capture(fixture.list());
                fixture.model().set(0, Path.of("replaced.zip"));
                FileDragAssertions.assertPendingUnavailable(fixture.list());
                FileDragAssertions.capture(fixture.list());
                fixture.available().set(false);
                FileDragAssertions.assertPendingUnavailable(fixture.list());
                FileDragAssertions.assertCannotStart(fixture.list());
                fixture.available().set(true);
                FileDragAssertions.assertFiles(fixture.list(), List.of(Path.of("replaced.zip")));
            }
        });
    }

    /// Null placeholders and explicitly unsupported values cannot create fallback paths or call providers for null rows.
    @Test
    public void rejectsNullAndUnsupportedOrdinaryRows() {
        EdtDispatcher.executeAndWait(() -> {
            DefaultListModel<@Nullable String> model = new DefaultListModel<>();
            model.addElement(null);
            model.addElement("invalid persisted path");
            JList<@Nullable String> list = new JList<>(model);
            list.setFixedCellHeight(68);
            list.setSize(400, 180);
            AtomicInteger lookups = new AtomicInteger();
            try (FileListDragSupport<@Nullable String> dragging = FileListDragSupport.forValues(list,
                    value -> { lookups.incrementAndGet(); return null; }, () -> 0L, () -> true)) {
                list.setSelectedIndex(0);
                FileDragAssertions.assertCannotStart(list);
                assertEquals(0, lookups.get());
                list.setSelectedIndex(1);
                FileDragAssertions.assertCannotStart(list);
                assertEquals(1, lookups.get());
            }
        });
    }

    /// Small movements remain clicks, cancellation permits a fresh gesture, and final close detaches all listeners.
    @Test
    public void preservesThresholdCancellationAndCleanupForValueRows() {
        EdtDispatcher.executeAndWait(() -> {
            try (Fixture fixture = fixture(List.of(Path.of("one.zip")))) {
                FileDragAssertions.capture(fixture.list());
                fixture.dragging().mouseDragged(new MouseEvent(fixture.list(), MouseEvent.MOUSE_DRAGGED,
                        2L, MouseEvent.BUTTON1_DOWN_MASK, 8, 34, 0, false, MouseEvent.NOBUTTON));
                assertNotNull(fixture.dragging().pendingTransfer());
                fixture.dragging().mouseReleased(new MouseEvent(fixture.list(), MouseEvent.MOUSE_RELEASED,
                        3L, 0, 8, 34, 1, false, MouseEvent.BUTTON1));
                assertNull(fixture.dragging().pendingTransfer());
                FileDragAssertions.assertFiles(fixture.list(), List.of(Path.of("one.zip")));
                fixture.dragging().close();
                assertNull(fixture.dragging().pendingTransfer());
                assertFalse(Arrays.asList(fixture.list().getMouseListeners()).contains(fixture.dragging()));
                assertFalse(Arrays.asList(fixture.list().getMouseMotionListeners()).contains(fixture.dragging()));
                assertFalse(Arrays.asList(fixture.model().getListDataListeners()).contains(fixture.dragging()));
            }
        });
    }

    /// Ordinary file sources retain existing ancestor imports and cannot import themselves or reorder their own model.
    @Test
    public void retainsAncestorImportsAndNeverReordersOrdinaryRows() {
        EdtDispatcher.executeAndWait(() -> {
            JPanel page = new JPanel();
            AtomicInteger imports = new AtomicInteger();
            try (ShellFileDropHandler.RouteRegistration registration = ShellFileDropHandler.registerFiles(
                    page, path -> path.toString().endsWith(".zip"), paths -> imports.incrementAndGet());
                    Fixture source = fixture(List.of(Path.of("source.zip")));
                    Fixture target = fixture(List.of(Path.of("target.zip"), Path.of("other.zip")))) {
                page.add(target.list());
                Transferable incoming = FileDragAssertions.capture(source.list());
                TransferHandler.TransferSupport drop = new TransferHandler.TransferSupport(target.list(), incoming);
                assertTrue(target.list().getTransferHandler().canImport(drop));
                assertTrue(target.list().getTransferHandler().importData(drop));
                assertEquals(1, imports.get());
                Transferable own = FileDragAssertions.capture(target.list());
                assertFalse(target.list().getTransferHandler().importData(
                        new TransferHandler.TransferSupport(target.list(), own)));
                assertEquals(TransferHandler.COPY, target.list().getTransferHandler().getSourceActions(target.list()));
                assertEquals(List.of(Path.of("target.zip"), Path.of("other.zip")),
                        java.util.Collections.list(target.model().elements()));
            }
        });
    }

    /// Creates an ordinary Path model with a snapshot token and a controllable operation gate.
    ///
    /// @param paths immutable actual local identities
    /// @return owned mutable Swing fixture
    private static Fixture fixture(@Unmodifiable List<Path> paths) {
        DefaultListModel<Path> model = new DefaultListModel<>();
        paths.forEach(model::addElement);
        JList<Path> list = new RowBoundsCheckedList<>(model, RowBoundsCheckedList.BlankClickPolicy.CLEAR);
        list.setFixedCellHeight(68);
        list.setSize(400, 240);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setSelectedIndex(0);
        AtomicInteger revision = new AtomicInteger();
        AtomicBoolean available = new AtomicBoolean(true);
        FileListDragSupport<Path> dragging = FileListDragSupport.forValues(list, path -> path,
                revision::get, available::get);
        return new Fixture(list, model, dragging, revision, available);
    }

    /// Mutable page fixture whose close releases only the support it owns.
    @NotNullByDefault
    private record Fixture(
            JList<Path> list,
            DefaultListModel<Path> model,
            FileListDragSupport<Path> dragging,
            AtomicInteger revision,
            AtomicBoolean available) implements AutoCloseable {
        /// Releases native drag listeners without touching the ordinary model contents.
        @Override
        public void close() {
            dragging.close();
        }
    }
}
