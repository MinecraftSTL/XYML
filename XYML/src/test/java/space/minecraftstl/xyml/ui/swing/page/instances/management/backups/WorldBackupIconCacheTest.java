// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.backups;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.backups.WorldBackupThumbnailTestSupport.archives;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.backups.WorldBackupThumbnailTestSupport.image;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.backups.WorldBackupThumbnailTestSupport.onEdt;

/// Verifies bounded optional image work, memory-only rendering, cache identity and actual-reader deletion coordination.
@NotNullByDefault
public final class WorldBackupIconCacheTest {
    /// Rendering and duplicate demand never create extra work, and ready icons are returned without reopening archives.
    @Test
    public void schedulesOnlyExplicitDemandAndCachesSuccessfulResults() {
        var rows = archives(3);
        var executor = new WorldBackupThumbnailTestSupport.QueuedExecutor();
        AtomicInteger reads = new AtomicInteger();
        Icon fallback = new ImageIcon(image(0xff777777));
        WorldBackupIconCache cache = onEdt(() -> new WorldBackupIconCache(executor, archive -> {
            assertFalse(SwingUtilities.isEventDispatchThread());
            reads.incrementAndGet();
            return image(0xff123456);
        }, fallback, () -> { }));
        try {
            EdtDispatcher.executeAndWait(() -> {
                cache.setIndex(rows);
                for (int i = 0; i < 500; i++) assertSame(fallback, cache.iconFor(rows.get(i % 3)));
                assertEquals(0, executor.size());
                cache.setDemand(rows);
                cache.setPaused(false);
                cache.setDemand(rows);
                assertEquals(2, executor.size());
            });
            executor.runAll();
            EdtDispatcher.executeAndWait(() -> {
                for (WorldBackupArchive row : rows) {
                    Icon icon = cache.iconFor(row);
                    assertNotSame(fallback, icon);
                    assertEquals(40, icon.getIconWidth());
                }
                cache.setDemand(rows);
            });
            assertEquals(3, reads.get());
            assertEquals(0, executor.size());
        } finally {
            EdtDispatcher.executeAndWait(cache::close);
        }
    }

    /// A renderer's retained icon view follows asynchronous readiness and eviction rather than pinning its first bitmap.
    @Test
    public void retainedRendererViewFollowsReadyAndInvalidatedCacheEntries() {
        var rows = archives(1);
        var executor = new WorldBackupThumbnailTestSupport.QueuedExecutor();
        WorldBackupIconCache cache = onEdt(() -> new WorldBackupIconCache(executor,
                archive -> image(0xff123456), new ImageIcon(image(0xff777777)), () -> { }));
        try {
            Icon view = onEdt(() -> {
                cache.setIndex(rows);
                Icon current = cache.viewFor(rows.get(0));
                assertEquals(0xff777777, paintedCenter(current));
                assertEquals(0, executor.size());
                cache.setDemand(rows);
                cache.setPaused(false);
                return current;
            });
            executor.runAll();
            EdtDispatcher.executeAndWait(() -> {
                assertEquals(0xff123456, paintedCenter(view));
                cache.setIndex(List.of());
                assertEquals(0xff777777, paintedCenter(view));
            });
        } finally {
            EdtDispatcher.executeAndWait(cache::close);
        }
    }

    /// Reads actual painted pixels without assuming a particular ImageIcon implementation or raster wrapper.
    ///
    /// @param icon ready image or stable cache view
    /// @return center ARGB pixel
    private static int paintedCenter(Icon icon) {
        BufferedImage canvas = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = canvas.createGraphics();
        try {
            icon.paintIcon(new javax.swing.JLabel(), graphics, 0, 0);
            return canvas.getRGB(20, 20);
        } finally {
            graphics.dispose();
            canvas.flush();
        }
    }

    /// Missing and failed optional images are negative-cached until explicit manual refresh.
    @Test
    public void cachesMissingAndFailedIconsAndRetriesOnlyExplicitly() {
        var rows = archives(2);
        var executor = new WorldBackupThumbnailTestSupport.QueuedExecutor();
        AtomicInteger reads = new AtomicInteger();
        Icon fallback = new ImageIcon(image(0xff777777));
        WorldBackupIconCache cache = onEdt(() -> new WorldBackupIconCache(executor, archive -> {
            reads.incrementAndGet();
            if (archive.fileName().equals("1.zip")) throw new IOException("controlled corrupt archive");
            return null;
        }, fallback, () -> { }));
        try {
            EdtDispatcher.executeAndWait(() -> { cache.setIndex(rows); cache.setDemand(rows); cache.setPaused(false); });
            executor.runAll();
            EdtDispatcher.executeAndWait(() -> {
                for (int i = 0; i < 100; i++) {
                    cache.setDemand(rows);
                    assertSame(fallback, cache.iconFor(rows.get(i % 2)));
                }
                assertEquals(0, executor.size());
                cache.retryFailures();
                cache.setDemand(rows);
            });
            executor.runAll();
            assertEquals(4, reads.get());
        } finally {
            EdtDispatcher.executeAndWait(cache::close);
        }
    }

    /// Offscreen jobs still in the executor queue are cancelled before they can open a file.
    @Test
    public void skipsQueuedOffscreenRequests() {
        var rows = archives(3);
        var executor = new WorldBackupThumbnailTestSupport.QueuedExecutor();
        java.util.List<String> readNames = new java.util.ArrayList<>();
        WorldBackupIconCache cache = onEdt(() -> new WorldBackupIconCache(executor, archive -> {
            readNames.add(archive.fileName());
            return image(0xff234567);
        }, new ImageIcon(image(0xff777777)), () -> { }));
        try {
            EdtDispatcher.executeAndWait(() -> {
                cache.setIndex(rows);
                cache.setDemand(rows.subList(0, 2));
                cache.setPaused(false);
                cache.setDemand(List.of(rows.get(2)));
            });
            executor.runAll();
            assertEquals(List.of("2.zip"), readNames);
        } finally {
            EdtDispatcher.executeAndWait(cache::close);
        }
    }

    /// Replacing one same-path record while its previous read is active cannot publish old pixels under the new row.
    @Test
    public void rejectsLateResultsAfterSamePathMetadataReplacement() {
        WorldBackupArchive original = archives(1).get(0);
        WorldBackupArchive replacement = new WorldBackupArchive(original.archive(), original.fileName(),
                original.sizeBytes() + 1L, original.lastModified().plusSeconds(1L));
        var executor = new WorldBackupThumbnailTestSupport.QueuedExecutor();
        Icon fallback = new ImageIcon(image(0xff777777));
        AtomicReference<@Nullable WorldBackupIconCache> reference = new AtomicReference<>();
        AtomicInteger repaints = new AtomicInteger();
        WorldBackupIconCache cache = onEdt(() -> new WorldBackupIconCache(executor, archive -> {
            if (archive.equals(original)) {
                EdtDispatcher.executeAndWait(() -> {
                    WorldBackupIconCache current = java.util.Objects.requireNonNull(reference.get());
                    current.setIndex(List.of(replacement));
                    current.setDemand(List.of(replacement));
                });
                return image(0xffff0000);
            }
            return image(0xff0000ff);
        }, fallback, repaints::incrementAndGet));
        reference.set(cache);
        try {
            EdtDispatcher.executeAndWait(() -> {
                cache.setIndex(List.of(original));
                cache.setDemand(List.of(original));
                cache.setPaused(false);
            });
            executor.runAll();
            EdtDispatcher.executeAndWait(() -> {
                assertSame(fallback, cache.iconFor(original));
                BufferedImage pixels = assertInstanceOf(BufferedImage.class,
                        assertInstanceOf(ImageIcon.class, cache.iconFor(replacement)).getImage());
                assertEquals(0xff0000ff, pixels.getRGB(20, 20));
            });
            assertEquals(1, repaints.get());
        } finally {
            EdtDispatcher.executeAndWait(cache::close);
        }
    }

    /// Positive and negative entries share a bounded LRU rather than growing with the complete backup directory.
    @Test
    public void evictsLeastRecentlyUsedEntriesAtTheConfiguredCapacity() {
        var rows = archives(300);
        var executor = new WorldBackupThumbnailTestSupport.QueuedExecutor();
        AtomicInteger reads = new AtomicInteger();
        Icon fallback = new ImageIcon(image(0xff777777));
        WorldBackupIconCache cache = onEdt(() -> new WorldBackupIconCache(executor, archive -> {
            reads.incrementAndGet();
            return image(0xff456789);
        }, fallback, () -> { }));
        try {
            EdtDispatcher.executeAndWait(() -> { cache.setIndex(rows); cache.setPaused(false); });
            for (int start = 0; start < rows.size(); start += 20) {
                var batch = rows.subList(start, Math.min(start + 20, rows.size()));
                EdtDispatcher.executeAndWait(() -> cache.setDemand(batch));
                executor.runAll();
            }
            assertEquals(300, reads.get());
            EdtDispatcher.executeAndWait(() -> {
                assertSame(fallback, cache.iconFor(rows.get(0)));
                assertNotSame(fallback, cache.iconFor(rows.get(299)));
                cache.setDemand(List.of(rows.get(0)));
            });
            executor.runAll();
            assertEquals(301, reads.get());
        } finally {
            EdtDispatcher.executeAndWait(cache::close);
        }
    }

    /// Deletion waits asynchronously for actual readers, not for queued cancelled tasks or the caller's executor.
    @Test
    @Timeout(15)
    public void limitsConcurrencyAndDrainsActualReadersWithoutBlockingEdt() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        WorldBackupIconCache cache = onEdt(() -> new WorldBackupIconCache(executor, archive -> {
            reads.incrementAndGet();
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("controlled reader timed out");
                return image(0xff334455);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(failure);
            } finally {
                active.decrementAndGet();
                released.incrementAndGet();
            }
        }, new ImageIcon(image(0xff777777)), () -> { }));
        try {
            EdtDispatcher.executeAndWait(() -> {
                var rows = archives(20);
                cache.setIndex(rows);
                cache.setDemand(rows);
                cache.setPaused(false);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(2, reads.get());
            CompletionStage<@Nullable Void> drained = onEdt(cache::pauseAndDrain);
            assertFalse(drained.toCompletableFuture().isDone());
            EdtDispatcher.executeAndWait(() -> assertEquals(2, active.get()));
            release.countDown();
            drained.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(0, active.get());
            assertEquals(2, released.get());
            assertEquals(2, peak.get());
            EdtDispatcher.executeAndWait(cache::close);
            assertFalse(executor.isShutdown());
        } finally {
            release.countDown();
            EdtDispatcher.executeAndWait(cache::close);
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /// Closing a page cancels queued work, preserves executor ownership, and never repaints from late completions.
    @Test
    public void closesWithoutReadingQueuedArchivesOrApplyingCallbacks() {
        var rows = archives(2);
        var executor = new WorldBackupThumbnailTestSupport.QueuedExecutor();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger repaints = new AtomicInteger();
        WorldBackupIconCache cache = onEdt(() -> new WorldBackupIconCache(executor, archive -> {
            reads.incrementAndGet();
            return image(0xff456789);
        }, new ImageIcon(image(0xff777777)), repaints::incrementAndGet));
        EdtDispatcher.executeAndWait(() -> {
            cache.setIndex(rows);
            cache.setDemand(rows);
            cache.setPaused(false);
            cache.close();
            cache.close();
            cache.setDemand(rows);
        });
        executor.runAll();
        assertEquals(0, reads.get());
        assertEquals(0, repaints.get());
    }

    /// A saturated shared executor degrades to the default icon instead of repeatedly throwing from painting.
    @Test
    public void cachesExecutorRejectionUntilManualRefresh() {
        AtomicInteger submissions = new AtomicInteger();
        var rows = archives(1);
        Icon fallback = new ImageIcon(image(0xff777777));
        WorldBackupIconCache cache = onEdt(() -> new WorldBackupIconCache(command -> {
            submissions.incrementAndGet();
            throw new RejectedExecutionException("controlled saturation");
        }, archive -> image(0xff123456), fallback, () -> { }));
        try {
            EdtDispatcher.executeAndWait(() -> {
                cache.setIndex(rows);
                cache.setDemand(rows);
                cache.setPaused(false);
                cache.setDemand(rows);
                assertSame(fallback, cache.iconFor(rows.get(0)));
                assertEquals(1, submissions.get());
                cache.retryFailures();
                cache.setDemand(rows);
                assertEquals(2, submissions.get());
            });
        } finally {
            EdtDispatcher.executeAndWait(cache::close);
        }
    }
}
