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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.ui.swing.choice.CatalogLayoutAssertions;
import space.minecraftstl.xyml.ui.swing.choice.RichValueListCellRenderer;
import space.minecraftstl.xyml.ui.swing.choice.RowBoundsCheckedList;

import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies serialized server-list persistence, failure recovery, and closed-panel callback suppression.
@NotNullByDefault
final class ServerCatalogPanelTest {
    /// Concurrent commands execute in submission order and cannot publish overlapping full-list writes.
    @Test
    void serializesMutationsWithoutLostUpdates() throws InterruptedException {
        BlockingAccess access = new BlockingAccess();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ServerCatalogPanel.ServerCatalogModel model = new ServerCatalogPanel.ServerCatalogModel(access, executor);
        try {
            model.load().join();
            CompletableFuture<ServerCatalogSnapshot> first = model.add(new ServerCatalogItem("A", "a.example"));
            assertTrue(access.firstWriteStarted.await(5, TimeUnit.SECONDS));
            CompletableFuture<ServerCatalogSnapshot> second = model.add(new ServerCatalogItem("B", "b.example"));

            assertFalse(access.secondWriteStarted.await(200, TimeUnit.MILLISECONDS));
            access.releaseFirstWrite.countDown();

            first.join();
            ServerCatalogSnapshot terminal = second.join();
            assertEquals(List.of("A", "B"), terminal.servers().stream().map(ServerCatalogItem::name).toList());
            assertEquals(2, access.writeCalls.get());
        } finally {
            access.releaseFirstWrite.countDown();
            model.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// A failed write retains the last successfully loaded rows instead of replacing them with an empty catalog.
    @Test
    void retainsLastSuccessfulRowsAfterWriteFailure() {
        ServerCatalogItem existing = new ServerCatalogItem("Existing", "existing.example");
        ServerCatalogAccess access = new ServerCatalogAccess() {
            /// Returns the stable fixture row.
            @Override
            public List<ServerCatalogItem> read() {
                return List.of(existing);
            }

            /// Rejects every attempted publication.
            @Override
            public void write(List<ServerCatalogItem> servers) throws IOException {
                throw new IOException("disk full");
            }
        };
        ServerCatalogPanel.ServerCatalogModel model = new ServerCatalogPanel.ServerCatalogModel(access, Runnable::run);

        model.load().join();
        ServerCatalogSnapshot failed = model.add(new ServerCatalogItem("New", "new.example")).join();

        assertEquals(ServerCatalogStatus.FAILURE, failed.status());
        assertEquals(List.of("Existing"), failed.servers().stream().map(ServerCatalogItem::name).toList());
        model.close();
    }

    /// An external replacement is reported and reloaded instead of being overwritten through a stale row index.
    @Test
    void rejectsExternalChangesBeforeIndexedMutation() {
        RevisionCheckingAccess access = new RevisionCheckingAccess(
                List.of(new ServerCatalogItem("Original", "original.example")));
        ServerCatalogPanel.ServerCatalogModel model = new ServerCatalogPanel.ServerCatalogModel(access, Runnable::run);
        model.load().join();
        access.replaceExternally(List.of(new ServerCatalogItem("External", "external.example")));

        ServerCatalogSnapshot failed = model.edit(0, "Edited", "edited.example").join();

        assertEquals(ServerCatalogStatus.FAILURE, failed.status());
        assertEquals(List.of("External"), failed.servers().stream().map(ServerCatalogItem::name).toList());
        assertEquals(0, access.successfulWrites);
        model.close();
    }

    /// A queued preceding mutation invalidates the exact order captured by a drag before it writes.
    @Test
    void rejectsCapturedDragAfterEarlierQueuedMutation() {
        ManualExecutor executor = new ManualExecutor();
        RevisionCheckingAccess access = new RevisionCheckingAccess(List.of(
                new ServerCatalogItem("A", "a.example"), new ServerCatalogItem("B", "b.example")));
        ServerCatalogPanel.ServerCatalogModel model = new ServerCatalogPanel.ServerCatalogModel(access, executor);
        try {
            CompletableFuture<ServerCatalogSnapshot> load = model.load();
            executor.runAll();
            List<ServerCatalogItem> captured = load.join().servers();
            CompletableFuture<ServerCatalogSnapshot> added = model.add(new ServerCatalogItem("C", "c.example"));
            CompletableFuture<ServerCatalogSnapshot> moved = model.move(captured, 0, 1);
            executor.runAll();
            assertEquals(ServerCatalogStatus.READY, added.join().status());
            assertEquals(ServerCatalogStatus.FAILURE, moved.join().status());
            assertEquals(List.of("A", "B", "C"), model.snapshot().servers().stream()
                    .map(ServerCatalogItem::name).toList());
            assertEquals(1, access.successfulWrites);
        } finally {
            model.close();
        }
    }

    /// External storage replacement is recovered without overwriting it using drag-captured indices.
    @Test
    void rejectsCapturedDragAfterExternalReplacement() {
        RevisionCheckingAccess access = new RevisionCheckingAccess(List.of(
                new ServerCatalogItem("A", "a.example"), new ServerCatalogItem("B", "b.example")));
        ServerCatalogPanel.ServerCatalogModel model = new ServerCatalogPanel.ServerCatalogModel(access, Runnable::run);
        try {
            List<ServerCatalogItem> captured = model.load().join().servers();
            access.replaceExternally(List.of(new ServerCatalogItem("External", "external.example")));
            ServerCatalogSnapshot failed = model.move(captured, 0, 1).join();
            assertEquals(ServerCatalogStatus.FAILURE, failed.status());
            assertEquals(List.of("External"), failed.servers().stream().map(ServerCatalogItem::name).toList());
            assertEquals(0, access.successfulWrites);
        } finally {
            model.close();
        }
    }

    /// Closing the panel prevents a delayed initial load from repopulating Swing components.
    @Test
    void closeSuppressesDelayedSwingPublication() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        AtomicReference<ServerCatalogPanel> panelReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panelReference.set(new ServerCatalogPanel(
                new FixedAccess(List.of(new ServerCatalogItem("Delayed", "delayed.example"))),
                executor)));
        ServerCatalogPanel panel = panelReference.get();
        SwingUtilities.invokeAndWait(panel::close);

        executor.runAll();
        SwingUtilities.invokeAndWait(() -> { });

        SwingUtilities.invokeAndWait(() -> assertEquals(0, panel.serverList().getModel().getSize()));
    }

    /// Allocated headings span the page and details stay beside the list at normal and narrow widths.
    @Test
    void laysOutActualListAndDetailsWithManagementTitle() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ServerCatalogPanel panel = new ServerCatalogPanel(new FixedAccess(List.of(
                    new ServerCatalogItem("示例多人游戏条目", "example.test:25565",
                            new CompoundTag().addString("motd", "本地保存的描述，不是服务端进程。")))), Runnable::run);
            try {
                assertInstanceOf(RichValueListCellRenderer.class, panel.serverList().getCellRenderer());
                assertInstanceOf(RowBoundsCheckedList.class, panel.serverList());
                panel.serverList().setFixedCellHeight(68);
                panel.serverList().setSize(520, 220);
                panel.serverList().setSelectedIndex(0);
                java.awt.Rectangle lastRow = java.util.Objects.requireNonNull(
                        panel.serverList().getCellBounds(0, 0));
                java.awt.Point blank = new java.awt.Point(
                        lastRow.x + 8, lastRow.y + lastRow.height + 20);
                dispatchPrimaryClick(panel.serverList(), blank);
                assertEquals(0, panel.serverList().getSelectedIndex(),
                        "Server blank click retains the selected row");

                assertEquals(space.minecraftstl.xyml.util.i18n.I18n.i18n("server.manage"),
                        CatalogLayoutAssertions.requireNamed(panel, "serverCatalogTitle", JLabel.class).getText());
                assertEquals("示例多人游戏条目",
                        CatalogLayoutAssertions.requireNamed(panel, "serverCatalogName", JTextArea.class).getText());
                assertEquals("example.test:25565",
                        CatalogLayoutAssertions.requireNamed(panel, "serverCatalogAddress", JTextArea.class).getText());
                assertEquals("本地保存的描述，不是服务端进程。",
                        CatalogLayoutAssertions.requireNamed(panel, "serverCatalogDescription", JTextArea.class).getText());
                assertNotNull(CatalogLayoutAssertions.requireNamed(panel, "serverCatalogIcon", JLabel.class).getIcon());
                for (int width : new int[] {1000, 720, 520}) {
                    CatalogLayoutAssertions.assertHorizontalWorkspace(panel, "serverCatalog", width, 460);
                }
                CatalogLayoutAssertions.assertHorizontalWorkspace(panel, "serverCatalog", 1000, 600);
                try {
                    CatalogLayoutAssertions.writePreview(panel, "server-management.png");
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            } finally {
                panel.close();
            }
        });
    }

    /// Blank space in short lists never selects the last entry, changes details, or starts a drag.
    @Test
    void shortListBlankClicksRetainSelectionAndDetails() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int count : new int[] {1, 3}) {
                List<ServerCatalogItem> entries = java.util.stream.IntStream.range(0, count)
                        .mapToObj(index -> new ServerCatalogItem("Server " + index, "server" + index + ".test",
                                new CompoundTag().addString("motd", "Description " + index)))
                        .toList();
                ServerCatalogPanel panel = new ServerCatalogPanel(new FixedAccess(entries), Runnable::run);
                try {
                    javax.swing.JList<ServerCatalogItem> list = panel.serverList();
                    list.setFixedCellHeight(68);
                    list.setSize(520, 360);
                    list.setSelectedIndex(0);
                    javax.swing.Icon originalIcon = CatalogLayoutAssertions.requireNamed(
                            panel, "serverCatalogIcon", JLabel.class).getIcon();
                    java.awt.Rectangle last = java.util.Objects.requireNonNull(list.getCellBounds(count - 1, count - 1));
                    java.awt.Point blank = new java.awt.Point(20, last.y + last.height + 25);
                    AtomicInteger changes = new AtomicInteger();
                    list.addListSelectionListener(event -> changes.incrementAndGet());
                    for (int repeat = 0; repeat < 3; repeat++) dispatchPrimaryClick(list, blank);
                    assertEquals(0, changes.get(), "Blank presses must not transiently select the last entry");
                    assertEquals(0, list.getSelectedIndex());
                    assertEquals(-1, list.locationToIndex(blank));
                    assertEquals("Server 0", CatalogLayoutAssertions.requireNamed(
                            panel, "serverCatalogName", JTextArea.class).getText());
                    assertEquals("server0.test", CatalogLayoutAssertions.requireNamed(
                            panel, "serverCatalogAddress", JTextArea.class).getText());
                    assertEquals("Description 0", CatalogLayoutAssertions.requireNamed(
                            panel, "serverCatalogDescription", JTextArea.class).getText());
                    assertEquals(originalIcon, CatalogLayoutAssertions.requireNamed(
                            panel, "serverCatalogIcon", JLabel.class).getIcon());
                    assertTrue(panel.canReorder());
                    java.awt.Rectangle target = java.util.Objects.requireNonNull(list.getCellBounds(count - 1, count - 1));
                    // The content icon is a normal row click, not a reorder gesture.
                    dispatchPrimaryClick(list, new java.awt.Point(target.x + 24, target.y + 24));
                    assertEquals(count - 1, list.getSelectedIndex());
                    list.clearSelection();
                    dispatchPrimaryClick(list, blank);
                    assertEquals(-1, list.getSelectedIndex(), "No selection must also remain unchanged");
                } finally {
                    panel.close();
                }
            }
        });
    }

    /// Dispatches a primary click through the real row-bounds list implementation.
    ///
    /// @param list target server list
    /// @param point list-coordinate click point
    private static void dispatchPrimaryClick(
            javax.swing.JList<?> list,
            java.awt.Point point) {
        long when = System.currentTimeMillis();
        list.dispatchEvent(new java.awt.event.MouseEvent(
                list, java.awt.event.MouseEvent.MOUSE_PRESSED, when, 0,
                point.x, point.y, 1, false, java.awt.event.MouseEvent.BUTTON1));
        list.dispatchEvent(new java.awt.event.MouseEvent(
                list, java.awt.event.MouseEvent.MOUSE_RELEASED, when + 1L, 0,
                point.x, point.y, 1, false, java.awt.event.MouseEvent.BUTTON1));
    }

    /// Access fixture that blocks the first write so overlap is observable.
    @NotNullByDefault
    private static final class BlockingAccess implements ServerCatalogAccess {
        /// Signals that the first write reached storage.
        private final CountDownLatch firstWriteStarted = new CountDownLatch(1);

        /// Releases the first write.
        private final CountDownLatch releaseFirstWrite = new CountDownLatch(1);

        /// Signals an overlapping second write.
        private final CountDownLatch secondWriteStarted = new CountDownLatch(1);

        /// Counts all writes.
        private final AtomicInteger writeCalls = new AtomicInteger();

        /// Current immutable storage rows.
        private volatile List<ServerCatalogItem> current = List.of();

        /// Returns current rows.
        @Override
        public List<ServerCatalogItem> read() {
            return current;
        }

        /// Records one publication and blocks the first call.
        @Override
        public void write(List<ServerCatalogItem> servers) throws IOException {
            int call = writeCalls.incrementAndGet();
            if (call == 1) {
                firstWriteStarted.countDown();
                await(releaseFirstWrite);
            } else if (call == 2) {
                secondWriteStarted.countDown();
            }
            current = List.copyOf(servers);
        }
    }

    /// Access fixture that compares every write with the revision established by its latest read.
    @NotNullByDefault
    private static final class RevisionCheckingAccess implements ServerCatalogPanel.RevisionAwareServerCatalogAccess {
        /// Current immutable storage rows.
        private List<ServerCatalogItem> current;

        /// Current storage revision.
        private int revision;

        /// Revision observed by the most recent read.
        private int observedRevision;

        /// Number of accepted writes.
        private int successfulWrites;

        /// Creates one fixture with initial rows.
        private RevisionCheckingAccess(List<ServerCatalogItem> initial) {
            current = List.copyOf(initial);
        }

        /// Establishes and returns the current revision.
        @Override
        public List<ServerCatalogItem> read() {
            observedRevision = revision;
            return current;
        }

        /// Replaces current rows without revision checking for interface compatibility.
        @Override
        public void write(List<ServerCatalogItem> servers) {
            current = List.copyOf(servers);
            revision++;
            successfulWrites++;
        }

        /// Rejects a write based on a stale preceding read.
        @Override
        public void writeIfUnchanged(
                List<ServerCatalogItem> expectedServers,
                List<ServerCatalogItem> servers) throws IOException {
            if (observedRevision != revision) {
                throw new IOException("external change");
            }
            write(servers);
        }

        /// Simulates Minecraft replacing the file after the model loaded it.
        private void replaceExternally(List<ServerCatalogItem> replacement) {
            current = List.copyOf(replacement);
            revision++;
        }
    }

    /// Immutable access fixture for delayed-load UI verification.
    @NotNullByDefault
    private static final class FixedAccess implements ServerCatalogAccess {
        /// Rows returned by every read.
        private final List<ServerCatalogItem> rows;

        /// Creates one fixed fixture.
        private FixedAccess(List<ServerCatalogItem> rows) {
            this.rows = List.copyOf(rows);
        }

        /// Returns fixed rows.
        @Override
        public List<ServerCatalogItem> read() {
            return rows;
        }

        /// Accepts no-op writes unused by this test.
        @Override
        public void write(List<ServerCatalogItem> servers) {
        }
    }

    /// Deterministic FIFO executor for delayed callback tests.
    @NotNullByDefault
    private static final class ManualExecutor implements Executor {
        /// Pending commands.
        private final Queue<Runnable> commands = new ArrayDeque<>();

        /// Queues one command.
        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        /// Runs every queued command in FIFO order.
        private void runAll() {
            while (!commands.isEmpty()) {
                commands.remove().run();
            }
        }
    }

    /// Waits for one test latch while preserving interruption as an I/O failure.
    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException("timed out waiting for test latch");
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for test latch", interruption);
        }
    }
}
