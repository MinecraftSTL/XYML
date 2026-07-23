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
package space.minecraftstl.xyml.ui.swing.page.schematics;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests shallow discovery, viewport parsing, navigation, cancellation, and closure.
@NotNullByDefault
public final class DefaultSchematicBrowserModelTest {
    /// Construction performs no I/O, and production discovery is shallow, filtered, and stable.
    @Test
    public void constructsWithoutIoAndScansOnlyDirectSupportedChildren() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = fileSystem.getPath("/schematics");
            Files.createDirectories(root.resolve("zeta/nested"));
            Files.createDirectories(root.resolve("Alpha"));
            Files.writeString(root.resolve("zeta/nested/hidden.litematic"), "invalid");
            Files.writeString(root.resolve("beta.litematic"), "invalid");
            Files.writeString(root.resolve("A.LITEMATIC"), "invalid");
            Files.writeString(root.resolve("ignored.txt"), "ignored");
            Files.createSymbolicLink(root.resolve("linked-directory"), root.resolve("zeta"));
            Files.createSymbolicLink(root.resolve("linked-file.litematic"), root.resolve("beta.litematic"));
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);

            assertAll(
                    () -> assertEquals(0, executor.pendingCount()),
                    () -> assertTrue(model.exactItemCount().isEmpty()),
                    () -> assertEquals(SchematicBrowserStatus.IDLE, model.snapshot().status()));

            CompletionStage<SchematicBrowserSnapshot> initialScan = model.loadIfNeeded();
            assertAll(
                    () -> assertEquals(1, executor.pendingCount()),
                    () -> assertFalse(initialScan.toCompletableFuture().isDone()),
                    () -> assertEquals(SchematicBrowserStatus.LOADING, model.snapshot().status()));
            executor.runNext();

            assertAll(
                    () -> assertEquals(4, initialScan.toCompletableFuture().join().itemCount().orElseThrow()),
                    () -> assertEquals(1L, model.snapshot().contentRevision()),
                    () -> assertEquals(SchematicBrowserStatus.READY, model.snapshot().status()));

            CompletionStage<ChoicePage<SchematicBrowserItem>> rows = model.load(
                    new IndexRange(0, 20), new LoadCancellation());
            executor.runNext();
            ChoicePage<SchematicBrowserItem> page = rows.toCompletableFuture().join();
            assertAll(
                    () -> assertEquals(new IndexRange(0, 4), page.range()),
                    () -> assertEquals(List.of("Alpha", "zeta", "A.LITEMATIC", "beta.litematic"),
                            page.items().stream().map(SchematicBrowserItem::fileName).toList()),
                    () -> assertInstanceOf(SchematicDirectoryItem.class, page.items().get(0)),
                    () -> assertInstanceOf(SchematicDirectoryItem.class, page.items().get(1)),
                    () -> assertFalse(assertInstanceOf(SchematicFileItem.class, page.items().get(2)).readable()),
                    () -> assertFalse(assertInstanceOf(SchematicFileItem.class, page.items().get(3)).readable()));
            model.close();
        }
    }

    /// Metadata parsing touches exactly the requested file indexes and preserves unreadable rows.
    @Test
    public void parsesOnlyFilesInsideTheRequestedRange() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        QueuedExecutor executor = new QueuedExecutor();
        List<Path> parsedPaths = new ArrayList<>();
        List<DefaultSchematicBrowserModel.DiscoveredEntry> entries = List.of(
                entry(root.resolve("folder"), true),
                entry(root.resolve("first.litematic"), false),
                entry(root.resolve("second.litematic"), false));
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (ignored, cancellation) -> entries,
                path -> {
                    parsedPaths.add(path);
                    throw new IOException("bad metadata: " + path.getFileName());
                });

        model.loadIfNeeded();
        executor.runNext();
        CompletionStage<ChoicePage<SchematicBrowserItem>> firstFile = model.load(
                new IndexRange(1, 2), new LoadCancellation());
        assertEquals(List.of(), parsedPaths);
        executor.runNext();
        SchematicFileItem unreadable = assertInstanceOf(
                SchematicFileItem.class,
                firstFile.toCompletableFuture().join().items().get(0));

        CompletionStage<ChoicePage<SchematicBrowserItem>> emptyTail = model.load(
                new IndexRange(20, 30), new LoadCancellation());
        executor.runNext();

        assertAll(
                () -> assertEquals(List.of(root.resolve("first.litematic")), parsedPaths),
                () -> assertFalse(unreadable.readable()),
                () -> assertNull(unreadable.metadata()),
                () -> assertNotNull(unreadable.failureMessage()),
                () -> assertEquals(new IndexRange(3, 3), emptyTail.toCompletableFuture().join().range()),
                () -> assertEquals(3, model.exactItemCount().orElseThrow()));
        model.close();
    }

    /// Lazy metadata loading rejects a scanned file replaced by a symbolic link before parsing.
    @Test
    public void rejectsAFileReplacedByASymbolicLinkBeforeLazyLoad() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            Path file = Files.writeString(root.resolve("replace.litematic"), "original");
            Path outside = Files.writeString(fileSystem.getPath("/outside.litematic"), "outside");
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                model.loadIfNeeded();
                executor.runNext();
                Files.delete(file);
                Files.createSymbolicLink(file, outside);

                CompletionStage<ChoicePage<SchematicBrowserItem>> load = model.load(
                        new IndexRange(0, 1), new LoadCancellation());
                executor.runNext();
                SchematicFileItem item = assertInstanceOf(
                        SchematicFileItem.class,
                        load.toCompletableFuture().join().items().get(0));

                assertAll(
                        () -> assertFalse(item.readable()),
                        () -> assertTrue(Objects.requireNonNull(item.failureMessage())
                                .contains("not a real Litematic file")));
            } finally {
                model.close();
            }
        }
    }

    /// A caller cancellation stops queued range parsing before metadata I/O begins.
    @Test
    public void cancelsAViewportRequestBeforeItParses() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        QueuedExecutor executor = new QueuedExecutor();
        AtomicInteger parseCalls = new AtomicInteger();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (ignored, cancellation) -> List.of(entry(root.resolve("one.litematic"), false)),
                path -> {
                    parseCalls.incrementAndGet();
                    throw new IOException("unreadable");
                });
        model.loadIfNeeded();
        executor.runNext();
        LoadCancellation cancellation = new LoadCancellation();
        CompletionStage<ChoicePage<SchematicBrowserItem>> load = model.load(
                new IndexRange(0, 1), cancellation);

        cancellation.cancel();
        executor.runNext();

        assertAll(
                () -> assertEquals(0, parseCalls.get()),
                () -> assertThrows(CancellationException.class, () -> load.toCompletableFuture().join()));
        model.close();
    }

    /// Refresh cancellation wins while an old range is between validation and terminal completion.
    @Test
    public void refreshCancelsARangeBeforeItsOldSuccessCompletes() throws Exception {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        CompletionGate completionGate = new CompletionGate();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (ignored, cancellation) -> List.of(entry(root.resolve("folder"), true)),
                path -> {
                    throw new IOException("unused metadata");
                },
                completionGate);
        try {
            model.loadIfNeeded().toCompletableFuture().join();
            completionGate.arm();
            CompletionStage<ChoicePage<SchematicBrowserItem>> oldRange = model.load(
                    new IndexRange(0, 1), new LoadCancellation());
            assertTrue(completionGate.awaitEntered());

            CompletionStage<SchematicBrowserSnapshot> refresh = model.refresh();

            assertThrows(CancellationException.class, () -> oldRange.toCompletableFuture().join());
            completionGate.release();
            assertEquals(SchematicBrowserStatus.READY, refresh.toCompletableFuture().join().status());
        } finally {
            completionGate.release();
            model.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Close cancellation wins while a committed scan is waiting to complete its public future.
    @Test
    public void closeCancelsAScanBeforeItsOldSuccessCompletes() throws Exception {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        CompletionGate completionGate = new CompletionGate();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (ignored, cancellation) -> List.of(),
                path -> {
                    throw new IOException("unused metadata");
                },
                completionGate);
        completionGate.arm();
        CompletionStage<SchematicBrowserSnapshot> oldScan = model.loadIfNeeded();
        try {
            assertTrue(completionGate.awaitEntered());

            model.close();

            assertThrows(CancellationException.class, () -> oldScan.toCompletableFuture().join());
        } finally {
            completionGate.release();
            model.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Refresh cancellation wins while loadIfNeeded is completing an already captured snapshot.
    @Test
    public void refreshCancelsAnOldNoOpSnapshotCompletion() throws Exception {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        CompletionGate completionGate = new CompletionGate();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (ignored, cancellation) -> List.of(),
                path -> {
                    throw new IOException("unused metadata");
                },
                completionGate);
        try {
            model.loadIfNeeded().toCompletableFuture().join();
            completionGate.arm();
            CompletionStage<SchematicBrowserSnapshot> oldSnapshot = model.loadIfNeeded();
            assertTrue(completionGate.awaitEntered());

            CompletionStage<SchematicBrowserSnapshot> refresh = model.refresh();

            assertThrows(CancellationException.class, () -> oldSnapshot.toCompletableFuture().join());
            completionGate.release();
            assertEquals(SchematicBrowserStatus.READY, refresh.toCompletableFuture().join().status());
        } finally {
            completionGate.release();
            model.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Close cancellation wins while root parent navigation is completing its unchanged snapshot.
    @Test
    public void closeCancelsAnOldRootNoOpSnapshotCompletion() throws Exception {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        CompletionGate completionGate = new CompletionGate();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (ignored, cancellation) -> List.of(),
                path -> {
                    throw new IOException("unused metadata");
                },
                completionGate);
        try {
            model.loadIfNeeded().toCompletableFuture().join();
            completionGate.arm();
            CompletionStage<SchematicBrowserSnapshot> oldSnapshot = model.returnToParent();
            assertTrue(completionGate.awaitEntered());

            model.close();

            assertThrows(CancellationException.class, () -> oldSnapshot.toCompletableFuture().join());
        } finally {
            completionGate.release();
            model.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// A concurrently finishing old scan cannot overwrite the latest refresh generation.
    @Test
    public void dropsAConcurrentOldScanResult() throws Exception {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        BlockingDirectoryReader reader = new BlockingDirectoryReader(root);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                reader,
                path -> {
                    throw new IOException("unused metadata");
                });
        try {
            CompletionStage<SchematicBrowserSnapshot> oldScan = model.loadIfNeeded();
            assertTrue(reader.awaitFirstStart());
            CompletionStage<SchematicBrowserSnapshot> latestScan = model.refresh();

            SchematicBrowserSnapshot latest = latestScan.toCompletableFuture().join();
            reader.releaseFirst();
            assertTrue(reader.awaitFirstReturn());

            assertAll(
                    () -> assertThrows(CancellationException.class, () -> oldScan.toCompletableFuture().join()),
                    () -> assertEquals(1, latest.itemCount().orElseThrow()),
                    () -> assertEquals(1L, latest.contentRevision()),
                    () -> assertEquals(SchematicBrowserStatus.READY, model.snapshot().status()));

            CompletionStage<ChoicePage<SchematicBrowserItem>> currentRows = model.load(
                    new IndexRange(0, 1), new LoadCancellation());
            SchematicFileItem current = assertInstanceOf(
                    SchematicFileItem.class,
                    currentRows.toCompletableFuture().join().items().get(0));
            assertEquals("latest.litematic", current.fileName());
        } finally {
            reader.releaseFirst();
            model.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Navigation accepts only known children, returns to root, and schedules root no-ops.
    @Test
    public void navigatesKnownDirectoriesWithoutCrossingTheRoot() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = fileSystem.getPath("/schematics");
            Path child = Files.createDirectories(root.resolve("child/grandchild")).getParent();
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            model.loadIfNeeded();
            executor.runNext();

            CompletionStage<SchematicBrowserSnapshot> unknown = model.openDirectory(root.resolve("unknown"));
            assertFalse(unknown.toCompletableFuture().isDone());
            executor.runNext();
            CompletionException unknownFailure = assertThrows(
                    CompletionException.class, () -> unknown.toCompletableFuture().join());
            assertInstanceOf(IllegalArgumentException.class, unknownFailure.getCause());

            CompletionStage<SchematicBrowserSnapshot> opened = model.openDirectory(child);
            executor.runNext();
            assertAll(
                    () -> assertEquals(child.toAbsolutePath().normalize(),
                            opened.toCompletableFuture().join().currentDirectory()),
                    () -> assertTrue(model.snapshot().canReturnToParent()),
                    () -> assertEquals(1, model.exactItemCount().orElseThrow()));

            CompletionStage<SchematicBrowserSnapshot> returned = model.returnToParent();
            executor.runNext();
            long rootRevision = returned.toCompletableFuture().join().contentRevision();
            assertAll(
                    () -> assertEquals(root.toAbsolutePath().normalize(), model.snapshot().currentDirectory()),
                    () -> assertFalse(model.snapshot().canReturnToParent()));

            CompletionStage<SchematicBrowserSnapshot> rootNoOp = model.returnToParent();
            assertFalse(rootNoOp.toCompletableFuture().isDone());
            executor.runNext();
            assertEquals(rootRevision, rootNoOp.toCompletableFuture().join().contentRevision());
            model.close();
        }
    }

    /// Root parent navigation during initial loading shares the active scan instead of returning LOADING.
    @Test
    public void rootParentNavigationSharesTheActiveScanCompletion() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        QueuedExecutor executor = new QueuedExecutor();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (ignored, cancellation) -> List.of(),
                path -> {
                    throw new IOException("unused metadata");
                });
        try {
            CompletionStage<SchematicBrowserSnapshot> initialScan = model.loadIfNeeded();
            CompletionStage<SchematicBrowserSnapshot> rootReturn = model.returnToParent();

            assertSame(initialScan, rootReturn);
            assertFalse(rootReturn.toCompletableFuture().isDone());
            executor.runNext();
            assertEquals(SchematicBrowserStatus.READY, rootReturn.toCompletableFuture().join().status());
        } finally {
            model.close();
        }
    }

    /// Root parent navigation supersedes a pending child scan instead of sharing its destination.
    @Test
    public void rootParentNavigationCancelsAPendingChildScan() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = fileSystem.getPath("/schematics");
            Path child = Files.createDirectories(root.resolve("child"));
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                model.loadIfNeeded();
                executor.runNext();
                CompletionStage<SchematicBrowserSnapshot> childScan = model.openDirectory(child);

                CompletionStage<SchematicBrowserSnapshot> rootReturn = model.returnToParent();

                assertThrows(CancellationException.class, () -> childScan.toCompletableFuture().join());
                executor.runNext();
                executor.runNext();
                assertAll(
                        () -> assertEquals(root.toAbsolutePath().normalize(),
                                rootReturn.toCompletableFuture().join().currentDirectory()),
                        () -> assertFalse(model.snapshot().canReturnToParent()));
            } finally {
                model.close();
            }
        }
    }

    /// Refresh rejects a current path whose intermediate descendant was replaced by an outside link.
    @Test
    public void rejectsAnIntermediateSymbolicLinkAfterNavigation() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = fileSystem.getPath("/schematics");
            Path parent = Files.createDirectories(root.resolve("parent/grandchild")).getParent();
            Path grandchild = parent.resolve("grandchild");
            Path outsideGrandchild = Files.createDirectories(fileSystem.getPath("/outside/grandchild"));
            Files.writeString(outsideGrandchild.resolve("external.litematic"), "invalid");
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                model.loadIfNeeded();
                executor.runNext();
                CompletionStage<SchematicBrowserSnapshot> openedParent = model.openDirectory(parent);
                executor.runNext();
                openedParent.toCompletableFuture().join();
                CompletionStage<SchematicBrowserSnapshot> openedGrandchild = model.openDirectory(grandchild);
                executor.runNext();
                openedGrandchild.toCompletableFuture().join();

                Files.delete(grandchild);
                Files.delete(parent);
                Files.createSymbolicLink(parent, fileSystem.getPath("/outside"));
                CompletionStage<SchematicBrowserSnapshot> refresh = model.refresh();
                executor.runNext();

                CompletionException failure = assertThrows(
                        CompletionException.class, () -> refresh.toCompletableFuture().join());
                assertAll(
                        () -> assertInstanceOf(IOException.class, failure.getCause()),
                        () -> assertEquals(SchematicBrowserStatus.ERROR, model.snapshot().status()),
                        () -> assertEquals(grandchild.toAbsolutePath().normalize(),
                                model.snapshot().currentDirectory()),
                        () -> assertEquals(0, model.exactItemCount().orElseThrow()));
            } finally {
                model.close();
            }
        }
    }

    /// Closing cancels queued scans and ranges, ignores queued work, and rejects new commands.
    @Test
    public void closeCancelsQueuedWorkAndRejectsNewCommands() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        QueuedExecutor executor = new QueuedExecutor();
        AtomicInteger scanCalls = new AtomicInteger();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (ignored, cancellation) -> {
                    scanCalls.incrementAndGet();
                    return List.of();
                },
                path -> {
                    throw new IOException("unused metadata");
                });
        CompletionStage<SchematicBrowserSnapshot> scan = model.loadIfNeeded();

        model.close();
        model.close();
        executor.runNext();

        assertAll(
                () -> assertEquals(0, scanCalls.get()),
                () -> assertThrows(CancellationException.class, () -> scan.toCompletableFuture().join()),
                () -> assertThrows(IllegalStateException.class, model::refresh),
                () -> assertThrows(IllegalStateException.class, model::loadIfNeeded),
                () -> assertThrows(IllegalStateException.class,
                        () -> model.load(new IndexRange(0, 1), new LoadCancellation())));
    }

    /// Creates one test descriptor with a file-name component matching the path.
    ///
    /// @param path descriptor path
    /// @param directory whether the path is a directory
    /// @return test descriptor
    private static DefaultSchematicBrowserModel.DiscoveredEntry entry(Path path, boolean directory) {
        return new DefaultSchematicBrowserModel.DiscoveredEntry(
                path.toAbsolutePath().normalize(), path.getFileName().toString(), directory);
    }

    /// One-shot test hook that pauses a terminal future immediately before its completion CAS.
    @NotNullByDefault
    private static final class CompletionGate implements Runnable {
        /// Signals that the armed completion boundary was reached.
        private final CountDownLatch entered = new CountDownLatch(1);

        /// Releases the paused completion boundary.
        private final CountDownLatch release = new CountDownLatch(1);

        /// Whether subsequent hook calls should wait at this boundary.
        private volatile boolean armed;

        /// Arms this one-shot boundary after setup completions have finished.
        private void arm() {
            armed = true;
        }

        /// Waits for a worker to reach the armed completion boundary.
        ///
        /// @return whether the boundary was reached within the test timeout
        /// @throws InterruptedException when the test thread is interrupted
        private boolean awaitEntered() throws InterruptedException {
            return entered.await(5, TimeUnit.SECONDS);
        }

        /// Releases every worker waiting at this completion boundary.
        private void release() {
            release.countDown();
        }

        /// Blocks only armed terminal completions without propagating test-thread interruption.
        @Override
        public void run() {
            if (!armed) {
                return;
            }
            entered.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /// Deterministic caller-owned executor that runs submitted work only when requested by a test.
    @NotNullByDefault
    private static final class QueuedExecutor implements Executor {
        /// Pending tasks in submission order.
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        /// Enqueues work without running it.
        ///
        /// @param command submitted work
        @Override
        public synchronized void execute(Runnable command) {
            tasks.addLast(command);
        }

        /// Runs the oldest queued task.
        private void runNext() {
            Runnable task;
            synchronized (this) {
                task = tasks.removeFirst();
            }
            task.run();
        }

        /// Returns the number of queued tasks.
        ///
        /// @return pending task count
        private synchronized int pendingCount() {
            return tasks.size();
        }
    }

    /// Reader whose first call returns late and whose second call returns the latest listing immediately.
    @NotNullByDefault
    private static final class BlockingDirectoryReader
            implements DefaultSchematicBrowserModel.DirectoryReader {
        /// Root used to create deterministic descriptors.
        private final Path root;

        /// Number of reader invocations.
        private final AtomicInteger calls = new AtomicInteger();

        /// Signals that the first read entered external work.
        private final CountDownLatch firstStarted = new CountDownLatch(1);

        /// Releases the intentionally stale first read.
        private final CountDownLatch releaseFirst = new CountDownLatch(1);

        /// Signals that the stale read returned to model code.
        private final CountDownLatch firstReturned = new CountDownLatch(1);

        /// Creates a controlled reader for one root.
        ///
        /// @param root descriptor root
        private BlockingDirectoryReader(Path root) {
            this.root = root;
        }

        /// Blocks the first read and immediately serves the second generation.
        ///
        /// @param directory ignored current directory
        /// @param cancellation intentionally ignored until the stale result is ready
        /// @return old or latest one-row listing
        /// @throws IOException when the wait is interrupted
        @Override
        public List<DefaultSchematicBrowserModel.DiscoveredEntry> read(
                Path directory,
                LoadCancellation cancellation) throws IOException {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstStarted.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release stale scan");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting to release stale scan", failure);
                } finally {
                    firstReturned.countDown();
                }
                return List.of(entry(root.resolve("old.litematic"), false));
            }
            return List.of(entry(root.resolve("latest.litematic"), false));
        }

        /// Waits for the stale reader to begin.
        ///
        /// @return whether the reader began before the timeout
        /// @throws InterruptedException when the test thread is interrupted
        private boolean awaitFirstStart() throws InterruptedException {
            return firstStarted.await(5, TimeUnit.SECONDS);
        }

        /// Allows the stale reader to return.
        private void releaseFirst() {
            releaseFirst.countDown();
        }

        /// Waits for the stale reader to return.
        ///
        /// @return whether the reader returned before the timeout
        /// @throws InterruptedException when the test thread is interrupted
        private boolean awaitFirstReturn() throws InterruptedException {
            return firstReturned.await(5, TimeUnit.SECONDS);
        }
    }
}
