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

import javax.swing.DropMode;
import javax.swing.JList;
import javax.swing.TransferHandler;
import java.awt.Component;
import java.awt.Container;
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
                Rectangle handle = RichChoiceListCellRenderer.dragHandleBounds(bounds);
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
