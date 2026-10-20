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
package space.minecraftstl.xyml.ui.swing.page.instances.management.servers;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.RichChoiceListCellRenderer;
import space.minecraftstl.xyml.ui.swing.choice.CatalogDragHitAssertions;

import javax.swing.DropMode;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JList;
import javax.swing.TransferHandler;
import java.awt.Component;
import java.awt.Container;
import java.awt.ComponentOrientation;
import java.awt.Point;
import java.awt.Cursor;
import java.awt.Rectangle;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises actual same-list drag payloads, serialized writes, selection retention, and cancellation gates.
///
/// Native OS drag gestures are not exercised by these headless component and storage integration tests.
@NotNullByDefault
final class ServerCatalogReorderSupportTest {
    /// First-to-last and last-to-first drops preserve row identity and select the moved row after saving.
    @Test
    void movesBothDirectionsAndSelectsOnlyAfterSaving() {
        Fixture fixture = new Fixture();
        try {
            EdtDispatcher.executeAndWait(() -> {
                assertEquals(DropMode.INSERT, fixture.panel.serverList().getDropMode());
                assertFalse(hasNamed(fixture.panel, "serverCatalogMoveUp"));
                assertFalse(hasNamed(fixture.panel, "serverCatalogMoveDown"));
                fixture.panel.serverList().setSelectedIndex(0);
                Transferable transfer = Objects.requireNonNull(fixture.handler().createTransferable(fixture.panel.serverList()));
                assertTrue(fixture.handler().drop(transfer, 3));
                assertEquals(List.of("A", "B", "C"), fixture.names());
                assertFalse(fixture.panel.serverList().isEnabled());
                assertFalse(fixture.handler().drop(transfer, 0));
            });
            fixture.drain();
            EdtDispatcher.executeAndWait(() -> {
                assertEquals(List.of("B", "C", "A"), fixture.names());
                assertEquals(2, fixture.panel.serverList().getSelectedIndex());
                assertTrue(fixture.panel.serverList().isEnabled());
                Transferable transfer = Objects.requireNonNull(fixture.handler().createTransferable(fixture.panel.serverList()));
                assertTrue(fixture.handler().drop(transfer, 0));
            });
            fixture.drain();
            EdtDispatcher.executeAndWait(() -> {
                assertEquals(List.of("A", "B", "C"), fixture.names());
                assertEquals(0, fixture.panel.serverList().getSelectedIndex());
            });
            assertEquals(2, fixture.access.writes);
        } finally {
            fixture.close();
        }
    }

    /// No-op gaps, out-of-range gaps, text imports, and another list's payload are rejected without writes.
    @Test
    void rejectsNoOpsInvalidAndForeignDrops() {
        Fixture fixture = new Fixture();
        Fixture other = new Fixture();
        try {
            EdtDispatcher.executeAndWait(() -> {
                fixture.panel.serverList().setSelectedIndex(1);
                Transferable transfer = Objects.requireNonNull(fixture.handler().createTransferable(fixture.panel.serverList()));
                for (int gap : new int[] {-1, 1, 2, 4}) assertFalse(fixture.handler().drop(transfer, gap));
                assertFalse(other.handler().drop(transfer, 0));
                assertFalse(fixture.handler().drop(new StringSelection("A"), 0));
                assertFalse(fixture.handler().canImport(new TransferHandler.TransferSupport(
                        fixture.panel.serverList(), transfer)), "Clipboard imports are not insertion drops");
                assertNull(fixture.handler().createTransferable(new JList<>()));
            });
            fixture.drain();
            assertEquals(0, fixture.access.writes);
            assertEquals(0, other.access.writes);
        } finally {
            fixture.close();
            other.close();
        }
    }

    /// A completed reorder invalidates earlier captured payloads, preventing stale index reuse.
    @Test
    void rejectsStaleSnapshotAndPostCloseDrops() {
        Fixture fixture = new Fixture();
        try {
            EdtDispatcher.executeAndWait(() -> {
                fixture.panel.serverList().setSelectedIndex(0);
                fixture.captured = fixture.handler().createTransferable(fixture.panel.serverList());
                assertNotNull(fixture.captured);
                assertTrue(fixture.handler().drop(fixture.captured, 3));
            });
            fixture.drain();
            EdtDispatcher.executeAndWait(() -> {
                ServerCatalogReorderSupport handler = fixture.handler();
                Transferable previous = Objects.requireNonNull(fixture.captured);
                assertFalse(handler.drop(previous, 0));
                fixture.panel.close();
                fixture.panel.close();
                assertNull(fixture.panel.serverList().getTransferHandler());
                assertFalse(handler.drop(previous, 0));
                assertNull(handler.createTransferable(fixture.panel.serverList()));
            });
            assertEquals(1, fixture.access.writes);
        } finally {
            fixture.close();
        }
    }

    /// A write failure restores the stored rows rather than displaying an order that was not persisted.
    @Test
    void retainsStoredOrderAfterWriteFailure() {
        Fixture fixture = new Fixture();
        try {
            fixture.access.failWrites = true;
            EdtDispatcher.executeAndWait(() -> {
                fixture.panel.serverList().setSelectedIndex(0);
                assertTrue(fixture.handler().drop(Objects.requireNonNull(
                        fixture.handler().createTransferable(fixture.panel.serverList())), 3));
            });
            fixture.drain();
            EdtDispatcher.executeAndWait(() -> {
                assertEquals(List.of("A", "B", "C"), fixture.names());
                assertFalse(fixture.panel.canReorder());
                assertTrue(fixture.panel.serverList().isEnabled());
            });
            assertEquals(0, fixture.access.writes);
        } finally {
            fixture.close();
        }
    }

    /// Hover and press affordances use the account renderer's right-side bounded handle.
    @Test
    void exposesHandleWithoutMakingWholeRowsDraggable() {
        Fixture fixture = new Fixture();
        try {
            EdtDispatcher.executeAndWait(() -> {
                JList<ServerCatalogItem> list = fixture.panel.serverList();
                list.setFixedCellHeight(RichChoiceListCellRenderer.ROW_HEIGHT);
                list.setSize(400, 250);
                Rectangle bounds = Objects.requireNonNull(list.getCellBounds(1, 1));
                Rectangle handle = CatalogDragHitAssertions.paintedHandle(list, 1);
                MouseEvent overHandle = new MouseEvent(list, MouseEvent.MOUSE_MOVED, 1L, 0,
                        handle.x + handle.width / 2, handle.y + handle.height / 2, 0, false);
                for (var listener : list.getMouseMotionListeners()) listener.mouseMoved(overHandle);
                assertEquals(Cursor.HAND_CURSOR, list.getCursor().getType());
                MouseEvent blank = new MouseEvent(list, MouseEvent.MOUSE_MOVED, 2L, 0, 398, 249, 0, false);
                for (var listener : list.getMouseMotionListeners()) listener.mouseMoved(blank);
                assertEquals(Cursor.DEFAULT_CURSOR, list.getCursor().getType());
                Component row = list.getCellRenderer().getListCellRendererComponent(
                        list, list.getModel().getElementAt(1), 1, false, false);
                assertTrue(hasNamed((Container) row, "richChoiceListIcon"), "Server icons must remain intact");
            });
        } finally {
            fixture.close();
        }
    }

    /// Mouse events at painted icon corners, dots, and padding start the same drag whether selected or not.
    @Test
    void startsDragFromWholePaintedIconWithEitherSelectionState() {
        Fixture fixture = new Fixture();
        try {
            EdtDispatcher.executeAndWait(() -> {
                JList<ServerCatalogItem> list = fixture.panel.serverList();
                list.setFixedCellHeight(RichChoiceListCellRenderer.ROW_HEIGHT);
                ServerCatalogReorderSupport real = fixture.handler();
                RecordingExport exporter = new RecordingExport(real);
                list.setTransferHandler(exporter);
                for (ComponentOrientation direction : new ComponentOrientation[] {
                        ComponentOrientation.LEFT_TO_RIGHT, ComponentOrientation.RIGHT_TO_LEFT}) {
                    list.applyComponentOrientation(direction);
                    for (int width : new int[] {240, 400, 720}) {
                        list.setSize(width, 250);
                        for (int selected : new int[] {-1, 0, 1, 2}) {
                            list.setSelectedIndex(selected);
                            Rectangle icon = CatalogDragHitAssertions.paintedHandle(list, 1);
                            for (Point point : CatalogDragHitAssertions.handlePoints(icon)) {
                                list.setSelectedIndex(selected);
                                exporter.exported = null;
                                list.dispatchEvent(mouse(list, MouseEvent.MOUSE_MOVED, point, 0));
                                assertEquals(Cursor.HAND_CURSOR, list.getCursor().getType(), point.toString());
                                list.dispatchEvent(mouse(list, MouseEvent.MOUSE_PRESSED, point, MouseEvent.BUTTON1));
                                list.dispatchEvent(mouse(list, MouseEvent.MOUSE_DRAGGED,
                                        new Point(point.x + 1, point.y + 1), MouseEvent.NOBUTTON));
                                assertNull(exporter.exported, "Sub-threshold movement must not start a drag");
                                // Cross into another row; BasicListUI may change selection before our handler runs.
                                list.dispatchEvent(mouse(list, MouseEvent.MOUSE_DRAGGED,
                                        new Point(point.x, point.y + 80), MouseEvent.NOBUTTON));
                                assertNotNull(exporter.exported, "Painted handle point must arm a drag: " + point);
                                assertEquals(1, exporter.sourceIndex, "The pressed row, not the pointer row, is exported");
                                list.dispatchEvent(mouse(list, MouseEvent.MOUSE_RELEASED, point, MouseEvent.BUTTON1));
                            }
                        }
                    }
                }
                list.setTransferHandler(real);
                list.setSelectedIndex(1);
                assertTrue(real.drop(Objects.requireNonNull(exporter.exported), 0));
            });
            fixture.drain();
            EdtDispatcher.executeAndWait(() -> assertEquals(List.of("B", "A", "C"), fixture.names()));
            assertEquals(1, fixture.access.writes);
        } finally {
            fixture.close();
        }
    }

    /// Scrolling changes viewport position but not list-coordinate handle capture; ordinary text cannot arm a drag.
    @Test
    void scrollAndCancelledPressDoNotChangeHandleSemantics() {
        Fixture fixture = new Fixture();
        try {
            EdtDispatcher.executeAndWait(() -> {
                JList<ServerCatalogItem> list = fixture.panel.serverList();
                list.setFixedCellHeight(RichChoiceListCellRenderer.ROW_HEIGHT);
                list.setSize(400, 250);
                JScrollPane scroll = new JScrollPane(list);
                scroll.setSize(400, 80);
                scroll.doLayout();
                scroll.getViewport().setViewPosition(new Point(0, 70));
                ServerCatalogReorderSupport real = fixture.handler();
                RecordingExport exporter = new RecordingExport(real);
                list.setTransferHandler(exporter);
                Rectangle icon = CatalogDragHitAssertions.paintedHandle(list, 1);
                Point center = new Point(icon.x + icon.width / 2, icon.y + icon.height / 2);
                list.dispatchEvent(mouse(list, MouseEvent.MOUSE_PRESSED, center, MouseEvent.BUTTON1));
                list.dispatchEvent(mouse(list, MouseEvent.MOUSE_RELEASED, center, MouseEvent.BUTTON1));
                list.dispatchEvent(mouse(list, MouseEvent.MOUSE_DRAGGED,
                        new Point(center.x, center.y + 10), MouseEvent.NOBUTTON));
                assertNull(exporter.exported, "A cancelled press cannot export");
                for (Point point : List.of(new Point(30, 100), new Point(398, 240))) {
                    list.dispatchEvent(mouse(list, MouseEvent.MOUSE_PRESSED, point, MouseEvent.BUTTON1));
                    list.dispatchEvent(mouse(list, MouseEvent.MOUSE_DRAGGED,
                            new Point(point.x, point.y + 10), MouseEvent.NOBUTTON));
                    assertNull(exporter.exported, "Text and row-exterior whitespace cannot arm a drag");
                    list.dispatchEvent(mouse(list, MouseEvent.MOUSE_RELEASED, point, MouseEvent.BUTTON1));
                }
                list.dispatchEvent(mouse(list, MouseEvent.MOUSE_PRESSED, center, MouseEvent.BUTTON1));
                list.dispatchEvent(mouse(list, MouseEvent.MOUSE_DRAGGED,
                        new Point(center.x, center.y + 10), MouseEvent.NOBUTTON));
                assertNotNull(exporter.exported);
                list.setTransferHandler(real);
                assertTrue(real.drop(exporter.exported, 0));
            });
            fixture.drain();
            assertEquals(1, fixture.access.writes);
        } finally {
            fixture.close();
        }
    }

    /// Creates a primary-button gesture event in list coordinates.
    ///
    /// @param list event owner
    /// @param id mouse event kind
    /// @param point list-coordinate pointer position
    /// @param button button value accepted by the event kind
    /// @return event routed through the list's actual listeners
    private static MouseEvent mouse(JList<?> list, int id, Point point, int button) {
        int modifiers = id == MouseEvent.MOUSE_DRAGGED || id == MouseEvent.MOUSE_PRESSED
                ? MouseEvent.BUTTON1_DOWN_MASK : 0;
        return new MouseEvent(list, id, 1L, modifiers, point.x, point.y, 1, false, button);
    }

    /// Replaces only the OS drag export to observe actual mouse-listener arming in a headless test.
    @NotNullByDefault
    private static final class RecordingExport extends TransferHandler {
        /// Real handler retaining payload validation and persistence behavior.
        private final ServerCatalogReorderSupport real;

        /// Captured payload, or null before the listener exports.
        private @Nullable Transferable exported;

        /// Selection when the actual gesture listener starts exporting.
        private int sourceIndex = -1;

        /// Keeps production transfer creation unchanged.
        ///
        /// @param real actual list handler
        private RecordingExport(ServerCatalogReorderSupport real) {
            this.real = real;
        }

        /// Observes export without opening a native system drag session.
        @Override
        public void exportAsDrag(JComponent component, java.awt.event.InputEvent event, int action) {
            assertEquals(MOVE, action);
            sourceIndex = ((JList<?>) component).getSelectedIndex();
            exported = real.createTransferable(component);
        }
    }

    /// Searches component names without manufacturing controls that are not mounted in the UI.
    ///
    /// @param root component hierarchy
    /// @param name expected component name
    /// @return whether the component exists
    private static boolean hasNamed(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return true;
            if (child instanceof Container container && hasNamed(container, name)) return true;
        }
        return false;
    }

    /// Owns one real panel with a deferred serialized storage executor.
    @NotNullByDefault
    private static final class Fixture implements AutoCloseable {
        /// Observable backing storage.
        private final RecordingAccess access = new RecordingAccess();

        /// Deferred storage operations.
        private final ManualExecutor executor = new ManualExecutor();

        /// Actual mounted panel.
        private final ServerCatalogPanel panel;

        /// Captured payload used by asynchronous boundary tests.
        private @Nullable Transferable captured;

        /// Creates the panel on the EDT and finishes its deferred initial read off the EDT.
        private Fixture() {
            java.util.concurrent.atomic.AtomicReference<@Nullable ServerCatalogPanel> created =
                    new java.util.concurrent.atomic.AtomicReference<>();
            EdtDispatcher.executeAndWait(() -> created.set(new ServerCatalogPanel(access, executor)));
            panel = Objects.requireNonNull(created.get());
            drain();
        }

        /// Returns the handler attached to the actual list.
        private ServerCatalogReorderSupport handler() {
            return (ServerCatalogReorderSupport) Objects.requireNonNull(panel.serverList().getTransferHandler());
        }

        /// Returns immutable visible row names on the EDT.
        private @Unmodifiable List<String> names() {
            return panel.displayedServers().stream().map(ServerCatalogItem::name).toList();
        }

        /// Completes worker operations and then their Swing continuations.
        private void drain() {
            while (!executor.commands.isEmpty()) executor.commands.remove().run();
            EdtDispatcher.executeAndWait(() -> { });
        }

        /// Closes the panel and verifies remaining operations cannot republish rows.
        @Override
        public void close() {
            EdtDispatcher.executeAndWait(panel::close);
            drain();
        }
    }

    /// Storage double that can reject writes without losing its last published rows.
    @NotNullByDefault
    private static final class RecordingAccess implements ServerCatalogAccess {
        /// Immutable stored row order.
        private @Unmodifiable List<ServerCatalogItem> rows = List.of(
                new ServerCatalogItem("A", "a.example"), new ServerCatalogItem("B", "b.example"),
                new ServerCatalogItem("C", "c.example"));

        /// Number of successful publications.
        private int writes;

        /// Whether writes fail before publishing new rows.
        private boolean failWrites;

        /// Reads stored rows on the worker channel.
        @Override
        public @Unmodifiable List<ServerCatalogItem> read() {
            return rows;
        }

        /// Publishes a detached immutable row list unless failure injection is active.
        @Override
        public void write(List<ServerCatalogItem> next) throws IOException {
            if (failWrites) throw new IOException("simulated disk full");
            rows = List.copyOf(next);
            writes++;
        }
    }

    /// Deterministic worker-only executor for asynchronous persistence tests.
    @NotNullByDefault
    private static final class ManualExecutor implements Executor {
        /// Pending storage operations in submission order.
        private final Queue<Runnable> commands = new ArrayDeque<>();

        /// Queues one command for the test's worker drain.
        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }
    }
}
