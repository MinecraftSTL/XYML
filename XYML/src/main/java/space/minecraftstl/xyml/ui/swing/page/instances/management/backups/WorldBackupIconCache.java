// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.backups;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Page-owned, bounded thumbnail cache; painting performs memory lookups only and never schedules ZIP work.
@NotNullByDefault
final class WorldBackupIconCache implements AutoCloseable {
    /// Highest number of submitted thumbnail tasks, including obsolete tasks awaiting their executor turn.
    static final int MAX_CONCURRENT_READS = 2;

    /// Highest number of positive and negative entries retained in the access-ordered cache.
    static final int MAX_CACHE_ENTRIES = 256;

    /// Injected blocking thumbnail boundary; implementations close all file handles before returning or throwing.
    @FunctionalInterface
    @NotNullByDefault
    interface Loader {
        /// Reads one optional owned image on a background worker.
        ///
        /// @param archive shallow indexed archive identity
        /// @return owned normalized image, or null when no supported thumbnail exists
        /// @throws IOException when optional archive or image data cannot be read
        @Nullable BufferedImage load(WorldBackupArchive archive) throws IOException;
    }

    /// Caller-owned executor, never shut down by this cache.
    private final Executor executor;

    /// Blocking read and decode boundary executed only by background jobs.
    private final Loader loader;

    /// Shared placeholder for pending, missing, and failed thumbnails.
    private final Icon fallback;

    /// EDT-only repaint callback, released on close so slow workers cannot retain a disposed page through it.
    private @Nullable Runnable repaint;

    /// EDT-only positive icons and explicit null negative entries in LRU order.
    private final Map<Key, @Nullable Icon> icons = new LinkedHashMap<>(32, 0.75f, true);

    /// EDT-only current visible and prefetched demand in priority order.
    private final Map<Key, WorldBackupArchive> demand = new LinkedHashMap<>();

    /// EDT-only submitted work, including requests no longer visible but not yet finished.
    private final Map<Key, Request> submitted = new LinkedHashMap<>();

    /// Immutable current index membership; no filesystem metadata lookup is performed by the renderer.
    private @Unmodifiable Set<Key> indexed = Set.of();

    /// Current index generation guarding callbacks after refresh or same-path replacement.
    private long generation;

    /// EDT-only scheduling pause used for hidden or busy pages.
    private boolean paused = true;

    /// Terminal lifecycle flag also read by background jobs.
    private volatile boolean closed;

    /// Guards transitions immediately before opening and after closing actual ZIP handles.
    private final Object readLock = new Object();

    /// Whether workers must not begin reading; guarded by readLock.
    private boolean readsPaused = true;

    /// Actual read operations that have begun, excluding cancelled queued work; guarded by readLock.
    private int activeReads;

    /// Promises waiting for all actual reads to close their file handles; guarded by readLock.
    private final List<CompletableFuture<@Nullable Void>> drains = new ArrayList<>();

    /// Creates an idle cache without opening files or starting work.
    ///
    /// @param executor caller-owned background executor
    /// @param loader blocking image boundary
    /// @param fallback shared default icon
    /// @param repaint EDT-only repaint callback
    WorldBackupIconCache(Executor executor, Loader loader, Icon fallback, Runnable repaint) {
        EdtDispatcher.requireEventDispatchThread();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.repaint = Objects.requireNonNull(repaint, "repaint");
    }

    /// Returns ready data or the fallback without opening a ZIP, inspecting metadata, or enqueuing work.
    ///
    /// @param archive indexed row to paint
    /// @return cached thumbnail or the shared fallback
    Icon iconFor(WorldBackupArchive archive) {
        EdtDispatcher.requireEventDispatchThread();
        return cachedIcon(Key.of(archive));
    }

    /// Returns a paint-only view that stays current even when a shared renderer caches its first icon per row.
    ///
    /// The view retains only the metadata key, never the decoded image, so renderer caching cannot bypass the LRU.
    ///
    /// @param archive indexed row identity
    /// @return stable memory-only view suitable for a caching cell renderer
    Icon viewFor(WorldBackupArchive archive) {
        EdtDispatcher.requireEventDispatchThread();
        return new CachedIcon(Key.of(archive));
    }

    /// Resolves one paint through the current bounded cache without performing I/O or scheduling a request.
    ///
    /// @param key immutable metadata identity
    /// @return ready image or the common fallback
    private Icon cachedIcon(Key key) {
        @Nullable Icon icon = icons.get(key);
        return icon == null ? fallback : icon;
    }

    /// Reconciles current metadata and invalidates late results without discarding unchanged positive thumbnails.
    ///
    /// @param archives current immutable shallow index
    void setIndex(@Unmodifiable List<WorldBackupArchive> archives) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) return;
        Set<Key> keys = new HashSet<>();
        for (WorldBackupArchive archive : archives) keys.add(Key.of(archive));
        indexed = Set.copyOf(keys);
        generation++;
        demand.clear();
        for (Request request : submitted.values()) request.obsolete = true;
        icons.keySet().removeIf(key -> !indexed.contains(key));
    }

    /// Allows an explicit manual refresh to retry negative results, but never retries them on paint.
    void retryFailures() {
        EdtDispatcher.requireEventDispatchThread();
        icons.entrySet().removeIf(entry -> entry.getValue() == null);
    }

    /// Replaces visible demand, cancelling only offscreen jobs that have not started reading.
    ///
    /// @param archives visible rows first, followed by the small prefetch margin
    void setDemand(@Unmodifiable List<WorldBackupArchive> archives) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) return;
        demand.clear();
        for (WorldBackupArchive archive : archives) {
            Key key = Key.of(archive);
            if (indexed.contains(key)) demand.putIfAbsent(key, archive);
            if (demand.size() >= MAX_CACHE_ENTRIES) break;
        }
        synchronized (readLock) {
            for (Request request : submitted.values()) {
                if (!request.started && !demand.containsKey(request.key)) request.obsolete = true;
            }
        }
        pump();
    }

    /// Pauses queued reads for hidden or busy pages without waiting for in-progress disk access on the EDT.
    ///
    /// @param pause whether new reads must remain paused
    void setPaused(boolean pause) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) return;
        paused = pause;
        synchronized (readLock) {
            readsPaused = pause;
            if (pause) {
                demand.clear();
                for (Request request : submitted.values()) {
                    if (!request.started) request.obsolete = true;
                }
            }
        }
        if (!pause) pump();
    }

    /// Stops new reads and asynchronously waits until actual readers have closed their ZIP handles.
    ///
    /// Completion remains valid after page close so an already confirmed deletion may still proceed.
    ///
    /// @return completion after all active file readers have exited
    CompletionStage<@Nullable Void> pauseAndDrain() {
        EdtDispatcher.requireEventDispatchThread();
        setPaused(true);
        synchronized (readLock) {
            if (activeReads == 0) return CompletableFuture.completedFuture(null);
            CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();
            drains.add(completion);
            return completion;
        }
    }

    /// Cancels future reads and discards cache state without shutting down the caller's executor or blocking the EDT.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) return;
        setPaused(true);
        closed = true;
        repaint = null;
        for (Request request : submitted.values()) request.obsolete = true;
        submitted.clear();
        demand.clear();
        icons.clear();
        indexed = Set.of();
    }

    /// Submits at most two jobs, caching executor rejection as a negative result until explicit refresh.
    private void pump() {
        if (closed || paused) return;
        for (Map.Entry<Key, WorldBackupArchive> entry : demand.entrySet()) {
            if (submitted.size() >= MAX_CONCURRENT_READS) break;
            if (icons.containsKey(entry.getKey()) || submitted.containsKey(entry.getKey())) continue;
            Request request = new Request(entry.getKey(), entry.getValue(), generation);
            submitted.put(request.key, request);
            try {
                executor.execute(() -> read(request));
            } catch (RuntimeException failure) {
                submitted.remove(request.key);
                icons.put(request.key, null);
                trim();
                LOG.warning("Failed to schedule optional backup thumbnail " + request.archive.archive(), failure);
                repaintIfOpen();
            }
        }
    }

    /// Enters the read gate immediately before calling the blocking loader.
    ///
    /// @param request submitted immutable archive request
    /// @return whether this job is still allowed to open a file
    private boolean beginRead(Request request) {
        synchronized (readLock) {
            if (closed || readsPaused || request.obsolete) return false;
            request.started = true;
            activeReads++;
            return true;
        }
    }

    /// Decodes on the worker, then closes the read gate before any confirmed delete continuation may run.
    ///
    /// @param request submitted archive identity
    private void read(Request request) {
        @Nullable Icon icon = null;
        boolean began = beginRead(request);
        if (began) {
            try {
                if (SwingUtilities.isEventDispatchThread()) {
                    throw new IllegalStateException("Backup thumbnail executors must not run reads on the EDT");
                }
                @Nullable BufferedImage image = loader.load(request.archive);
                if (image != null) icon = new ImageIcon(image);
            } catch (IOException | RuntimeException failure) {
                LOG.warning("Could not load optional backup thumbnail " + request.archive.archive(), failure);
            } finally {
                endRead();
            }
        }
        @Nullable Icon resolved = icon;
        SwingUtilities.invokeLater(() -> completed(request, resolved, began));
    }

    /// Completes drain promises outside the gate only after the blocking loader has released all handles.
    private void endRead() {
        @Unmodifiable List<CompletableFuture<@Nullable Void>> ready = List.of();
        synchronized (readLock) {
            activeReads--;
            if (activeReads == 0 && !drains.isEmpty()) {
                ready = List.copyOf(drains);
                drains.clear();
            }
        }
        for (CompletableFuture<@Nullable Void> completion : ready) completion.complete(null);
    }

    /// Applies only current identities on the EDT, then admits the next visible request.
    ///
    /// @param request completed request
    /// @param icon decoded icon or null negative result
    /// @param began whether the request actually called the loader rather than being cancelled in the queue
    private void completed(Request request, @Nullable Icon icon, boolean began) {
        EdtDispatcher.requireEventDispatchThread();
        if (submitted.get(request.key) == request) submitted.remove(request.key);
        if (closed) return;
        if (began && !request.obsolete && request.generation == generation && indexed.contains(request.key)) {
            icons.put(request.key, icon);
            trim();
            repaintIfOpen();
        }
        pump();
    }

    /// Notifies only a live owner without retaining its widget callback after terminal close.
    private void repaintIfOpen() {
        @Nullable Runnable callback = repaint;
        if (!closed && callback != null) callback.run();
    }

    /// Enforces the shared positive and negative LRU capacity without retaining open archive objects.
    private void trim() {
        while (icons.size() > MAX_CACHE_ENTRIES) {
            var iterator = icons.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    /// Stable renderer-owned view that never pins an image after its bounded cache entry is evicted.
    @NotNullByDefault
    private final class CachedIcon implements Icon {
        /// Metadata identity only; no open file or decoded bitmap is retained here.
        private final Key key;

        /// Captures a pure memory lookup key.
        ///
        /// @param key indexed archive metadata identity
        private CachedIcon(Key key) {
            this.key = key;
        }

        /// Draws the latest ready image or fallback using only the EDT-confined cache.
        @Override
        public void paintIcon(@Nullable Component component, Graphics graphics, int x, int y) {
            EdtDispatcher.requireEventDispatchThread();
            cachedIcon(key).paintIcon(component, graphics, x, y);
        }

        /// Returns the fixed row icon width without opening or scheduling work.
        @Override
        public int getIconWidth() {
            return WorldBackupThumbnailLoader.ICON_SIZE;
        }

        /// Returns the fixed row icon height without opening or scheduling work.
        @Override
        public int getIconHeight() {
            return WorldBackupThumbnailLoader.ICON_SIZE;
        }
    }

    /// Immutable identity taken entirely from the shallow catalog snapshot.
    ///
    /// @param path normalized archive path
    /// @param size archive bytes
    /// @param modified indexed modification instant
    @NotNullByDefault
    private record Key(Path path, long size, Instant modified) {
        /// Builds a cache identity without querying the filesystem.
        ///
        /// @param archive current shallow catalog record
        /// @return immutable archive identity
        private static Key of(WorldBackupArchive archive) {
            return new Key(archive.archive(), archive.sizeBytes(), archive.lastModified());
        }
    }

    /// Submitted request; only cancellation is volatile and started is guarded by readLock.
    @NotNullByDefault
    private static final class Request {
        /// Stable snapshot identity.
        private final Key key;

        /// Immutable record passed to the worker.
        private final WorldBackupArchive archive;

        /// Index generation at submission time.
        private final long generation;

        /// Whether a queued or obsolete-generation request must be ignored.
        private volatile boolean obsolete;

        /// Whether the loader has begun; guarded by the owning cache readLock.
        private boolean started;

        /// Captures one immutable request with independent lifecycle flags.
        ///
        /// @param key immutable cache identity
        /// @param archive shallow catalog metadata
        /// @param generation current index generation
        private Request(Key key, WorldBackupArchive archive, long generation) {
            this.key = key;
            this.archive = archive;
            this.generation = generation;
        }
    }
}
