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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.io.DeletionMode;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/// Shared deterministic thumbnail fixtures; no native windows or real backup mutations are used.
@NotNullByDefault
final class WorldBackupThumbnailTestSupport {
    /// Prevents utility construction.
    private WorldBackupThumbnailTestSupport() {
    }

    /// Calls a value-producing fixture action on the EDT without allowing a null result.
    ///
    /// @param action UI action
    /// @param <T> returned non-null type
    /// @return action result
    static <T> T onEdt(Supplier<T> action) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(action.get()));
        return java.util.Objects.requireNonNull(result.get());
    }

    /// Flushes callbacks already posted to the EDT without sleeping or blocking that thread.
    static void drainEdt() {
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Creates an owned normalized image without decoding files.
    ///
    /// @param color requested ARGB color
    /// @return independently owned opaque test image
    static BufferedImage image(int color) {
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(color, true));
            graphics.fillRect(0, 0, 40, 40);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /// Creates immutable shallow metadata without opening any fixture files.
    ///
    /// @param count desired indexed rows
    /// @return immutable distinct archive identities
    static @Unmodifiable List<WorldBackupArchive> archives(int count) {
        List<WorldBackupArchive> archives = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            archives.add(new WorldBackupArchive(Path.of("build", "thumbnail-fixtures", i + ".zip"),
                    i + ".zip", i + 10L, Instant.ofEpochSecond(i + 100L)));
        }
        return List.copyOf(archives);
    }

    /// Executor whose queued tasks are explicitly run on the JUnit thread, never the Swing EDT.
    @NotNullByDefault
    static final class QueuedExecutor implements Executor {
        /// Work submitted by the EDT and drained by the test thread; guarded by this queue.
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        /// Records one pending worker without executing it inline.
        @Override
        public void execute(Runnable command) {
            synchronized (tasks) {
                tasks.add(command);
            }
        }

        /// Returns the number of executor tasks not yet run.
        ///
        /// @return pending task count
        int size() {
            synchronized (tasks) {
                return tasks.size();
            }
        }

        /// Runs one worker away from the EDT and flushes its completion callbacks.
        void runNext() {
            if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Tests must drain workers off the EDT");
            @Nullable Runnable task;
            synchronized (tasks) {
                task = tasks.poll();
            }
            if (task != null) task.run();
            drainEdt();
        }

        /// Drains the bounded pipeline while guarding against an accidental infinite requeue loop.
        void runAll() {
            drainEdt();
            for (int i = 0; i < 10_000; i++) {
                if (size() == 0) {
                    drainEdt();
                    if (size() == 0) return;
                }
                runNext();
            }
            throw new AssertionError("Thumbnail queue did not become idle");
        }
    }

    /// In-memory catalog exposing index replacement and confirmed-delete observation.
    @NotNullByDefault
    static final class Catalog implements WorldBackupCatalog {
        /// Current shallow immutable result, also returned to asynchronous delete continuations.
        volatile WorldBackupSnapshot snapshot;

        /// Optional callback before an in-memory confirmed deletion.
        volatile Runnable beforeDelete = () -> { };

        /// Optional observation after the complete in-memory delete mutation.
        volatile Runnable afterDelete = () -> { };

        /// Number of catalog scans, independent of thumbnail reads.
        final AtomicInteger loads = new AtomicInteger();

        /// Number of confirmed deletion calls.
        final AtomicInteger deletes = new AtomicInteger();

        /// Creates one fixture catalog.
        ///
        /// @param archives immutable displayed archive order
        Catalog(@Unmodifiable List<WorldBackupArchive> archives) {
            snapshot = new WorldBackupSnapshot(List.of(new WorldBackupSource(Path.of("saves", "world"), "world")), archives);
        }

        /// Returns a synthetic save directory without filesystem access.
        @Override
        public Path savesDirectory() {
            return Path.of("saves");
        }

        /// Returns a synthetic backup directory without filesystem access.
        @Override
        public Path backupsDirectory() {
            return Path.of("backups");
        }

        /// Publishes the current controlled shallow index.
        @Override
        public CompletionStage<WorldBackupSnapshot> load() {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Retains fixture metadata instead of compressing a real world.
        @Override
        public CompletionStage<WorldBackupSnapshot> createBackup(WorldBackupSource source) {
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Records and removes only the selected fixture identity.
        @Override
        public CompletionStage<WorldBackupSnapshot> deleteBackup(WorldBackupArchive archive) {
            beforeDelete.run();
            deletes.incrementAndGet();
            snapshot = new WorldBackupSnapshot(snapshot.sources(), snapshot.archives().stream()
                    .filter(item -> !item.archive().equals(archive.archive())).toList());
            afterDelete.run();
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Records no filesystem work for fixture restoration.
        @Override
        public CompletionStage<WorldBackupSnapshot> restoreBackup(WorldBackupArchive archive, String destinationName) {
            return CompletableFuture.completedFuture(snapshot);
        }
    }

    /// Deterministic confirmations that never display native dialogs.
    @NotNullByDefault
    static final class Interactions implements WorldBackupInteractions {
        /// Completes a no-op desktop command.
        @Override
        public CompletionStage<@Nullable Void> openDirectory(Path directory) {
            return CompletableFuture.completedFuture(null);
        }

        /// Accepts deletion inside the in-memory catalog only.
        @Override
        public boolean confirmDelete(Component owner, WorldBackupArchive archive) {
            return true;
        }

        /// Avoids platform recycle-bin inspection during tests.
        @Override
        public DeletionMode chooseDeleteMode(Component owner, WorldBackupArchive archive) {
            return DeletionMode.PERMANENT;
        }

        /// Supplies a synthetic new save name.
        @Override
        public String requestRestoreDestination(Component owner, WorldBackupArchive archive) {
            return "restored-thumbnail";
        }

        /// Accepts the fixture destination without user interaction.
        @Override
        public boolean confirmRestore(Component owner, WorldBackupArchive archive, String destinationName) {
            return true;
        }

        /// Fails any unexpected backup-command error instead of opening a dialog.
        @Override
        public void showFailure(Component owner, String title, String detail) {
            throw new AssertionError(title + ": " + detail);
        }
    }
}
