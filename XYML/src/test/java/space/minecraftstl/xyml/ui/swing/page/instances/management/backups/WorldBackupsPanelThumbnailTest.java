// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.backups;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.CatalogLayoutAssertions;
import space.minecraftstl.xyml.ui.swing.choice.FileDragAssertions;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.HierarchyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.backups.WorldBackupThumbnailTestSupport.archives;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.backups.WorldBackupThumbnailTestSupport.drainEdt;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.backups.WorldBackupThumbnailTestSupport.image;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.backups.WorldBackupThumbnailTestSupport.onEdt;

/// Headless viewport integration for actual thumbnail rows without resuming native desktop control.
@NotNullByDefault
public final class WorldBackupsPanelThumbnailTest {
    /// Isolated real ZIP used by the production-loader-to-renderer integration test.
    @TempDir
    private Path directory;

    /// A real world's PNG reaches the large row, replacing the floppy fallback without parsing level data.
    @Test
    public void paintsActualWorldPngFromZipThroughProductionLoader() throws Exception {
        Path path = directory.resolve("实际备份.zip");
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        BufferedImage source = image(0xff2b7ca4);
        ImageIO.write(source, "PNG", png);
        source.flush();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("测试世界/level.dat"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("测试世界/icon.png"));
            zip.write(png.toByteArray());
            zip.closeEntry();
        }
        WorldBackupArchive archive = new WorldBackupArchive(path, path.getFileName().toString(), Files.size(path),
                Files.getLastModifiedTime(path).toInstant());
        var catalog = new WorldBackupThumbnailTestSupport.Catalog(List.of(archive));
        var executor = new WorldBackupThumbnailTestSupport.QueuedExecutor();
        WorldBackupsPanel panel = onEdt(() -> new WorldBackupsPanel(catalog,
                new WorldBackupThumbnailTestSupport.Interactions(), executor,
                WorldBackupThumbnailLoader::load, list -> true));
        try {
            EdtDispatcher.executeAndWait(() -> { panel.activate(); prepareViewport(panel, 0, 2); });
            executor.runAll();
            EdtDispatcher.executeAndWait(() -> {
                JList<?> list = archiveList(panel);
                assertEquals(0xff2b7ca4, color(iconAt(list, 0)));
                list.setSelectedIndex(0);
                FileDragAssertions.assertFiles(list, List.of(path));
            });
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
            executor.runAll();
        }
        assertTrue(Files.isRegularFile(path));
    }

    /// Construction, hidden layout measurement and repeated painting do not eagerly open every indexed archive.
    @Test
    public void loadsVisibleRowsAndTwoNeighborsOnlyAndReusesImagesAfterHideShow() {
        var rows = archives(500);
        var catalog = new WorldBackupThumbnailTestSupport.Catalog(rows);
        var executor = new WorldBackupThumbnailTestSupport.QueuedExecutor();
        AtomicBoolean visible = new AtomicBoolean(false);
        Set<String> reads = new HashSet<>();
        WorldBackupsPanel panel = onEdt(() -> new WorldBackupsPanel(catalog,
                new WorldBackupThumbnailTestSupport.Interactions(), executor, archive -> {
                    reads.add(archive.fileName());
                    return image(0xff000000 | (int) archive.sizeBytes());
                }, list -> visible.get()));
        try {
            EdtDispatcher.executeAndWait(() -> {
                panel.setSize(1000, 800);
                layout(panel);
                assertEquals(0, catalog.loads.get());
                panel.activate();
                JList<?> list = prepareViewport(panel, 0, 2);
                renderAll(list);
                list.getPreferredSize();
            });
            executor.runAll();
            assertTrue(reads.isEmpty());
            EdtDispatcher.executeAndWait(() -> {
                visible.set(true);
                JList<?> list = prepareViewport(panel, 5, 2);
                fireVisibility(list);
            });
            executor.runAll();
            Set<String> expected = onEdt(() -> {
                JList<?> list = archiveList(panel);
                int first = list.getFirstVisibleIndex();
                int last = list.getLastVisibleIndex();
                Set<String> names = new HashSet<>();
                for (int i = Math.max(0, first - 2); i <= Math.min(rows.size() - 1, last + 2); i++) {
                    names.add(rows.get(i).fileName());
                }
                assertTrue(names.size() < 20);
                list.setSelectedIndex(first);
                assertEquals(0xff000000 | (int) rows.get(first).sizeBytes(), color(iconAt(list, first)));
                FileDragAssertions.assertFiles(list, List.of(rows.get(first).archive()));
                renderAll(list);
                list.getPreferredSize();
                return Set.copyOf(names);
            });
            executor.runAll();
            assertEquals(expected, reads);
            int count = reads.size();
            EdtDispatcher.executeAndWait(() -> {
                visible.set(false);
                fireVisibility(archiveList(panel));
            });
            executor.runAll();
            EdtDispatcher.executeAndWait(() -> {
                visible.set(true);
                fireVisibility(archiveList(panel));
            });
            executor.runAll();
            assertEquals(count, reads.size());
            assertEquals(0, executor.size());
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
            executor.runAll();
        }
    }

    /// Explicit refresh retries failed icons, retains unchanged positive images and reloads same-path replacements.
    @Test
    public void retriesNegativeResultsAndInvalidatesReplacedArchiveMetadata() {
        WorldBackupArchive row = archives(1).get(0);
        var catalog = new WorldBackupThumbnailTestSupport.Catalog(List.of(row));
        var executor = new WorldBackupThumbnailTestSupport.QueuedExecutor();
        AtomicInteger reads = new AtomicInteger();
        WorldBackupsPanel panel = onEdt(() -> new WorldBackupsPanel(catalog,
                new WorldBackupThumbnailTestSupport.Interactions(), executor, archive -> {
                    if (reads.incrementAndGet() == 1) return null;
                    return image(archive.sizeBytes() == row.sizeBytes() ? 0xff0000ff : 0xffff0000);
                }, list -> true));
        try {
            EdtDispatcher.executeAndWait(() -> { panel.activate(); prepareViewport(panel, 0, 2); });
            executor.runAll();
            EdtDispatcher.executeAndWait(() -> {
                JList<?> list = archiveList(panel);
                assertNotEquals(0xff0000ff, color(iconAt(list, 0)));
                for (int i = 0; i < 100; i++) renderAll(list);
            });
            executor.runAll();
            assertEquals(1, reads.get());
            EdtDispatcher.executeAndWait(() -> refresh(panel));
            executor.runAll();
            assertEquals(2, reads.get());
            EdtDispatcher.executeAndWait(() -> {
                assertEquals(0xff0000ff, color(iconAt(archiveList(panel), 0)));
                refresh(panel);
            });
            executor.runAll();
            assertEquals(2, reads.get());
            WorldBackupArchive replacement = new WorldBackupArchive(row.archive(), row.fileName(), row.sizeBytes() + 1L,
                    row.lastModified().plusSeconds(1L));
            EdtDispatcher.executeAndWait(() -> {
                archiveList(panel).setSelectedIndex(0);
                catalog.snapshot = new WorldBackupSnapshot(catalog.snapshot.sources(), List.of(replacement));
                refresh(panel);
            });
            executor.runAll();
            assertEquals(3, reads.get());
            EdtDispatcher.executeAndWait(() -> {
                JList<?> list = archiveList(panel);
                assertEquals(replacement, list.getSelectedValue());
                assertEquals(0xffff0000, color(iconAt(list, 0)));
            });
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
            executor.runAll();
        }
    }

    /// Confirmed deletion starts after reader handles close even if its page is closed while waiting.
    @Test
    @Timeout(15)
    public void waitsForReaderReleaseWithoutBlockingEdtOrCancellingConfirmedDeleteOnClose() throws Exception {
        var catalog = new WorldBackupThumbnailTestSupport.Catalog(archives(1));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch deleted = new CountDownLatch(1);
        AtomicBoolean readerClosed = new AtomicBoolean();
        AtomicBoolean deletedAfterClose = new AtomicBoolean();
        catalog.beforeDelete = () -> deletedAfterClose.set(readerClosed.get());
        catalog.afterDelete = deleted::countDown;
        WorldBackupsPanel panel = onEdt(() -> new WorldBackupsPanel(catalog,
                new WorldBackupThumbnailTestSupport.Interactions(), executor, archive -> {
                    readStarted.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("controlled read timed out");
                        return image(0xff123456);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException(failure);
                    } finally {
                        readerClosed.set(true);
                    }
                }, list -> true));
        try {
            EdtDispatcher.executeAndWait(() -> {
                panel.activate();
                prepareViewport(panel, 0, 2).setSelectedIndex(0);
            });
            assertTrue(readStarted.await(5, TimeUnit.SECONDS));
            EdtDispatcher.executeAndWait(() -> {
                CatalogLayoutAssertions.requireNamed(panel, "worldBackupsDelete", JButton.class).doClick();
                assertEquals(0, catalog.deletes.get());
                assertFalse(readerClosed.get());
                panel.close();
            });
            release.countDown();
            assertTrue(deleted.await(5, TimeUnit.SECONDS));
            assertTrue(deletedAfterClose.get());
            assertEquals(1, catalog.deletes.get());
            drainEdt();
            EdtDispatcher.executeAndWait(() -> assertEquals(0, panel.getComponentCount()));
            assertFalse(executor.isShutdown());
        } finally {
            release.countDown();
            EdtDispatcher.executeAndWait(panel::close);
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /// Returns the ordinary archive value list owned by the page.
    ///
    /// @param panel initialized page
    /// @return page-owned list
    private static JList<?> archiveList(WorldBackupsPanel panel) {
        return CatalogLayoutAssertions.requireNamed(panel, "worldBackupsList", JList.class);
    }

    /// Establishes real viewport geometry without native windows or overriding the production list type.
    ///
    /// @param panel page owning the scroll pane
    /// @param first desired first visible row
    /// @param count desired visible row count
    /// @return sized page-owned list
    private static JList<?> prepareViewport(WorldBackupsPanel panel, int first, int count) {
        panel.setSize(1000, 800);
        layout(panel);
        JList<?> list = archiveList(panel);
        JScrollPane scroll = CatalogLayoutAssertions.requireNamed(panel, "worldBackupsScroll", JScrollPane.class);
        int height = Math.max(68, list.getModel().getSize() * 68);
        list.setSize(600, height);
        scroll.getViewport().setExtentSize(new Dimension(600, count * 68));
        scroll.getViewport().setViewSize(new Dimension(600, height));
        scroll.getViewport().setViewPosition(new Point(0, first * 68));
        return list;
    }

    /// Fires the same visibility notification used when a cached page leaves or re-enters the hierarchy.
    ///
    /// @param list actual page-owned list
    private static void fireVisibility(JList<?> list) {
        list.dispatchEvent(new HierarchyEvent(list, HierarchyEvent.HIERARCHY_CHANGED,
                list, list.getParent(), HierarchyEvent.SHOWING_CHANGED));
    }

    /// Exercises all renderer calls that Swing may make during width measurement without queuing I/O.
    ///
    /// @param list ordinary page list
    /// @param <T> actual row value type
    private static <T> void renderAll(JList<T> list) {
        for (int i = 0; i < list.getModel().getSize(); i++) {
            list.getCellRenderer().getListCellRendererComponent(list, list.getModel().getElementAt(i), i, false, false);
        }
    }

    /// Returns one actual renderer icon without making a thumbnail request through painting.
    ///
    /// @param list page-owned list
    /// @param index existing row index
    /// @param <T> actual row value type
    /// @return configured row icon
    private static <T> Icon iconAt(JList<T> list, int index) {
        Component component = list.getCellRenderer().getListCellRendererComponent(
                list, list.getModel().getElementAt(index), index, list.isSelectedIndex(index), false);
        JComponent row = assertInstanceOf(JComponent.class, component);
        JLabel label = CatalogLayoutAssertions.requireNamed(row, "richChoiceListIcon", JLabel.class);
        return Objects.requireNonNull(label.getIcon());
    }

    /// Reads a fixture thumbnail pixel after asynchronous publication.
    ///
    /// @param icon decoded image icon
    /// @return center ARGB pixel
    private static int color(Icon icon) {
        BufferedImage painted = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = painted.createGraphics();
        try {
            icon.paintIcon(new JLabel(), graphics, 0, 0);
            return painted.getRGB(20, 20);
        } finally {
            graphics.dispose();
            painted.flush();
        }
    }

    /// Invokes only the page's existing refresh command.
    ///
    /// @param panel active page
    private static void refresh(WorldBackupsPanel panel) {
        CatalogLayoutAssertions.requireNamed(panel, "worldBackupsRefresh", JButton.class).doClick();
    }

    /// Lays out existing components recursively without creating native peers.
    ///
    /// @param container component subtree
    private static void layout(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) layout(child);
        }
    }
}
