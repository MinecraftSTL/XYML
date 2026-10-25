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
import java.awt.Point;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies opt-in file-only exports, stale gesture rejection, and composed external drop routing.
@NotNullByDefault
public final class FileListDragSupportTest {
    /// Isolated source directory used only by tests, never a user's actual files.
    @TempDir
    private Path temporaryDirectory;

    /// Files, disabled names, directories, and Unicode names remain untouched after clipboard export.
    @Test
    public void exportsOnlyActualFilesInDisplayOrderWithoutMovingSources() throws Exception {
        Path first = Files.writeString(temporaryDirectory.resolve("模组 one.jar.disabled"), "first");
        Path directory = Files.createDirectory(temporaryDirectory.resolve("world two"));
        EdtDispatcher.executeAndWait(() -> {
            try (Fixture fixture = fixture(List.of(first, directory), Function.identity())) {
                fixture.list().setSelectedIndices(new int[] {1, 0});
                FileDragAssertions.assertFiles(fixture.list(), List.of(first, directory));
                Clipboard clipboard = new Clipboard("isolated file transfer");
                fixture.list().getTransferHandler().exportToClipboard(fixture.list(), clipboard, TransferHandler.COPY);
                Transferable transfer = Objects.requireNonNull(clipboard.getContents(null));
                assertFalse(transfer.isDataFlavorSupported(DataFlavor.stringFlavor));
                assertThrows(UnsupportedFlavorException.class, () -> transfer.getTransferData(DataFlavor.stringFlavor));
                assertThrows(UnsupportedOperationException.class, () ->
                        ((List<?>) transfer.getTransferData(DataFlavor.javaFileListFlavor)).clear());
                assertNull(fixture.support().pendingTransfer());
            }
        });
        assertEquals("first", Files.readString(first));
        assertTrue(Files.isDirectory(directory));
    }

    /// Empty space, right-button presses, loading rows, and failed rows never arm exports.
    @Test
    public void ignoresNonFileRowsAndNonPrimaryGestures() {
        EdtDispatcher.executeAndWait(() -> {
            try (Fixture fixture = fixture(List.of(Path.of("one.jar")), Function.identity())) {
                fixture.model().addElement(ChoiceListEntry.loading(1));
                fixture.model().addElement(ChoiceListEntry.failed(2, new IllegalStateException("fixture")));
                for (Point point : List.of(new Point(8, 200), new Point(8, 65), new Point(8, 125))) {
                    fixture.support().mousePressed(press(fixture.list(), point, MouseEvent.BUTTON1));
                    assertNull(fixture.support().pendingTransfer());
                }
                fixture.support().mousePressed(press(fixture.list(), new Point(8, 10), MouseEvent.BUTTON3));
                assertNull(fixture.support().pendingTransfer());
            }
        });
    }

    /// Unsupported model implementations and mixed selections cannot fabricate local file paths.
    @Test
    public void refusesMissingPathsAndUnloadedSelectedRows() {
        EdtDispatcher.executeAndWait(() -> {
            try (Fixture fixture = fixture(List.of(Path.of("one.jar")), path -> null)) {
                fixture.support().mousePressed(press(fixture.list(), new Point(8, 10), MouseEvent.BUTTON1));
                assertNull(fixture.support().pendingTransfer());
            }
            try (Fixture fixture = fixture(List.of(Path.of("one.jar")), Function.identity())) {
                fixture.model().addElement(ChoiceListEntry.loading(1));
                fixture.list().setSelectedIndices(new int[] {0, 1});
                fixture.support().mousePressed(press(fixture.list(), new Point(8, 10), MouseEvent.BUTTON1));
                assertNull(fixture.support().pendingTransfer());
            }
        });
    }

    /// Repository switches, refreshes, selection changes, disabling, and closing invalidate pending payloads.
    @Test
    public void rejectsStaleContextsRowsAndSelections() {
        EdtDispatcher.executeAndWait(() -> {
            try (Fixture fixture = fixture(List.of(Path.of("one.jar"), Path.of("two.jar")), Function.identity())) {
                FileDragAssertions.capture(fixture.list());
                fixture.revision().incrementAndGet();
                assertNull(fixture.support().pendingTransfer());
                FileDragAssertions.capture(fixture.list());
                fixture.model().set(0, ChoiceListEntry.loaded(0, Path.of("replaced.jar")));
                assertNull(fixture.support().pendingTransfer());
                FileDragAssertions.capture(fixture.list());
                fixture.list().setSelectedIndex(1);
                assertNull(fixture.support().pendingTransfer());
                FileDragAssertions.capture(fixture.list());
                fixture.available().set(false);
                assertNull(fixture.support().pendingTransfer());
                fixture.available().set(true);
                FileDragAssertions.capture(fixture.list());
                fixture.list().setEnabled(false);
                assertNull(fixture.support().pendingTransfer());
            }
        });
    }

    /// A movement below the native threshold is still a click, and cancelled gestures can be repeated.
    @Test
    public void preservesSmallMovementsAndResetsCancelledGestures() {
        EdtDispatcher.executeAndWait(() -> {
            try (Fixture fixture = fixture(List.of(Path.of("one.jar")), Function.identity())) {
                FileDragAssertions.capture(fixture.list());
                fixture.support().mouseDragged(new MouseEvent(fixture.list(), MouseEvent.MOUSE_DRAGGED, 2L,
                        MouseEvent.BUTTON1_DOWN_MASK, 8, 30, 0, false, MouseEvent.NOBUTTON));
                assertNotNull(fixture.support().pendingTransfer());
                fixture.support().mouseReleased(press(fixture.list(), new Point(8, 30), MouseEvent.BUTTON1));
                assertNull(fixture.support().pendingTransfer());
                FileDragAssertions.assertFiles(fixture.list(), List.of(Path.of("one.jar")));
            }
        });
    }

    /// Page-scoped external import routes survive an installed child export handler, but self-import is rejected.
    @Test
    public void composesAncestorImportsAndRejectsItsOwnPayload() {
        EdtDispatcher.executeAndWait(() -> {
            JPanel page = new JPanel();
            AtomicInteger imports = new AtomicInteger();
            try (ShellFileDropHandler.RouteRegistration registration = ShellFileDropHandler.registerFiles(
                    page, path -> path.toString().endsWith(".jar"), paths -> imports.incrementAndGet());
                    Fixture source = fixture(List.of(Path.of("source.jar")), Function.identity());
                    Fixture target = fixture(List.of(Path.of("target.jar")), Function.identity())) {
                page.add(target.list());
                Transferable transfer = FileDragAssertions.capture(source.list());
                TransferHandler.TransferSupport drop = new TransferHandler.TransferSupport(target.list(), transfer);
                assertTrue(target.list().getTransferHandler().canImport(drop));
                assertTrue(target.list().getTransferHandler().importData(drop));
                assertEquals(1, imports.get());
                Transferable own = FileDragAssertions.capture(target.list());
                assertFalse(target.list().getTransferHandler().canImport(
                        new TransferHandler.TransferSupport(target.list(), own)));
            }
        });
    }

    /// Listener cleanup is idempotent and restores the original clipboard/import handler without global activation.
    @Test
    public void restoresOwnedStateAndLeavesOtherListsOptedOut() {
        EdtDispatcher.executeAndWait(() -> {
            DefaultListModel<ChoiceListEntry<Path>> model = new DefaultListModel<>();
            JList<ChoiceListEntry<Path>> list = new JList<>(model);
            @Nullable TransferHandler previous = list.getTransferHandler();
            int mouseCount = list.getMouseListeners().length;
            int motionCount = list.getMouseMotionListeners().length;
            int dataCount = model.getListDataListeners().length;
            FileListDragSupport<Path> support = new FileListDragSupport<>(list, Function.identity(), () -> 0L, () -> true);
            support.close();
            support.close();
            assertSame(previous, list.getTransferHandler());
            assertEquals(mouseCount, list.getMouseListeners().length);
            assertEquals(motionCount, list.getMouseMotionListeners().length);
            assertEquals(dataCount, model.getListDataListeners().length);
            assertNull(support.pendingTransfer());
        });
    }

    /// Reserved sorting areas cannot produce files, and the composed handler still accepts its previous route.
    @Test
    public void preservesReservedHandleAndPreviousImportHandler() {
        EdtDispatcher.executeAndWait(() -> {
            DefaultListModel<ChoiceListEntry<Path>> model = new DefaultListModel<>();
            model.addElement(ChoiceListEntry.loaded(0, Path.of("one.zip")));
            JList<ChoiceListEntry<Path>> list = new JList<>(model);
            list.setFixedCellHeight(60);
            list.setSize(300, 60);
            list.setSelectedIndex(0);
            AtomicInteger imports = new AtomicInteger();
            TransferHandler previous = new TransferHandler() {
                /// Accepts this deterministic fixture's delegated import.
                @Override
                public boolean canImport(TransferSupport support) {
                    return true;
                }

                /// Records delegated imports without reading files.
                @Override
                public boolean importData(TransferSupport support) {
                    imports.incrementAndGet();
                    return true;
                }
            };
            list.setTransferHandler(previous);
            try (FileListDragSupport<Path> support = new FileListDragSupport<>(
                    list, Function.identity(), () -> 0L, () -> true, point -> point.x > 250)) {
                support.mousePressed(press(list, new Point(280, 10), MouseEvent.BUTTON1));
                assertNull(support.pendingTransfer());
                TransferHandler.TransferSupport external = new TransferHandler.TransferSupport(list,
                        new java.awt.datatransfer.StringSelection("existing private transfer"));
                assertTrue(list.getTransferHandler().importData(external));
                assertEquals(1, imports.get());
            }
            assertSame(previous, list.getTransferHandler());
        });
    }

    /// A reserved priority handle delegates MOVE only to its existing internal handler, never as external files.
    @Test
    public void delegatesOnlyReservedPriorityMoves() {
        EdtDispatcher.executeAndWait(() -> {
            DefaultListModel<ChoiceListEntry<Path>> model = new DefaultListModel<>();
            model.addElement(ChoiceListEntry.loaded(0, Path.of("priority.zip")));
            JList<ChoiceListEntry<Path>> list = new JList<>(model);
            list.setFixedCellHeight(60);
            list.setSize(300, 60);
            list.setSelectedIndex(0);
            AtomicInteger moves = new AtomicInteger();
            TransferHandler internal = new TransferHandler() {
                /// Records only delegated priority moves without starting native drag-and-drop.
                @Override
                public void exportAsDrag(javax.swing.JComponent source, java.awt.event.InputEvent event, int action) {
                    assertEquals(TransferHandler.MOVE, action);
                    moves.incrementAndGet();
                }
            };
            list.setTransferHandler(internal);
            try (FileListDragSupport<Path> support = new FileListDragSupport<>(
                    list, Function.identity(), () -> 0L, () -> true, point -> point.x >= 250)) {
                support.mousePressed(press(list, new Point(280, 30), MouseEvent.BUTTON1));
                MouseEvent drag = new MouseEvent(list, MouseEvent.MOUSE_DRAGGED, 2L,
                        MouseEvent.BUTTON1_DOWN_MASK, 280, 45, 0, false, MouseEvent.NOBUTTON);
                list.getTransferHandler().exportAsDrag(list, drag, TransferHandler.COPY);
                assertEquals(0, moves.get());
                list.getTransferHandler().exportAsDrag(list, drag, TransferHandler.MOVE);
                assertEquals(1, moves.get());
                assertNull(support.pendingTransfer());
            }
            assertSame(internal, list.getTransferHandler());
        });
    }

    /// Creates a loaded test list with explicit dimensions, immutable row paths, and independent lifecycle gates.
    ///
    /// @param paths actual local path identities
    /// @param provider deterministic path resolver, which may reject a row
    /// @return owned fixture closed on the EDT
    private static Fixture fixture(@Unmodifiable List<Path> paths, Function<Path, @Nullable Path> provider) {
        DefaultListModel<ChoiceListEntry<Path>> model = new DefaultListModel<>();
        for (int i = 0; i < paths.size(); i++) model.addElement(ChoiceListEntry.loaded(i, paths.get(i)));
        JList<ChoiceListEntry<Path>> list = new RowBoundsCheckedList<>(model, RowBoundsCheckedList.BlankClickPolicy.CLEAR);
        list.setFixedCellHeight(60);
        list.setFixedCellWidth(300);
        list.setSize(300, 240);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setSelectedIndex(0);
        AtomicInteger revision = new AtomicInteger();
        AtomicBoolean available = new AtomicBoolean(true);
        FileListDragSupport<Path> support = new FileListDragSupport<>(list, provider, () -> revision.get(), available::get);
        return new Fixture(list, model, support, revision, available);
    }

    /// Creates one primary or secondary mouse press without invoking Swing's native UI delegate.
    ///
    /// @param list source list
    /// @param point list-local point
    /// @param button pressed mouse button
    /// @return synthetic deterministic press
    private static MouseEvent press(JList<?> list, Point point, int button) {
        return new MouseEvent(list, MouseEvent.MOUSE_PRESSED, 1L,
                button == MouseEvent.BUTTON1 ? MouseEvent.BUTTON1_DOWN_MASK : MouseEvent.BUTTON3_DOWN_MASK,
                point.x, point.y, 1, false, button);
    }

    /// Mutable isolated Swing fixture; closing releases its explicitly owned support.
    @NotNullByDefault
    private record Fixture(
            JList<ChoiceListEntry<Path>> list,
            DefaultListModel<ChoiceListEntry<Path>> model,
            FileListDragSupport<Path> support,
            AtomicInteger revision,
            AtomicBoolean available) implements AutoCloseable {
        /// Releases listeners on the EDT without touching source paths.
        @Override
        public void close() {
            support.close();
        }
    }
}
