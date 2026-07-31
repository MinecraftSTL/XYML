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
package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that a World tab indexes only paths first and materializes NBT rows per viewport range.
@NotNullByDefault
final class DefaultWorldCatalogModelTest {
    /// Temporary source root used to give fake catalog paths deterministic absolute values.
    @TempDir
    private Path temporaryDirectory;

    /// Shallow indexing never constructs every world; only the requested logical range is materialized.
    @Test
    void defersWorldMetadataUntilTheViewportRequestsItsRange() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RecordingAccess access = new RecordingAccess(temporaryDirectory, 12);
        DefaultWorldCatalogModel model = new DefaultWorldCatalogModel(
                access,
                executor,
                WorldCatalogStrings.english());
        try {
            assertEquals(WorldCatalogStatus.IDLE, model.snapshot().status());
            assertEquals(0, access.indexCalls());
            assertTrue(access.materializedDirectories().isEmpty());

            CompletableFuture<WorldCatalogSnapshot> ready = new CompletableFuture<>();
            Subscription subscription = model.subscribe(change -> {
                WorldCatalogSnapshot snapshot = change.currentValue();
                if (snapshot != null && snapshot.status() == WorldCatalogStatus.READY) {
                    ready.complete(snapshot);
                }
            });
            try {
                model.loadIfNeeded();
                WorldCatalogSnapshot snapshot = ready.get(5, TimeUnit.SECONDS);
                assertEquals(12, snapshot.itemCount().orElseThrow());
            } finally {
                subscription.unsubscribe();
            }

            assertEquals(1, access.indexCalls());
            assertTrue(access.materializedDirectories().isEmpty());

            ChoicePage<WorldCatalogItem> page = model.load(
                    IndexRange.ofLength(4, 3),
                    new LoadCancellation()).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(new IndexRange(4, 7), page.range());
            assertEquals(3, page.items().size());
            assertEquals(access.directories().subList(4, 7), access.materializedDirectories());
            assertFalse(access.materializedDirectories().contains(access.directories().get(0)));
            assertFalse(access.materializedDirectories().contains(access.directories().get(11)));
        } finally {
            model.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Copy and export reuse the selected materialized row while refreshing only the shallow path index.
    @Test
    void delegatesCopyAndExportWithoutMaterializingUnrequestedRows() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RecordingAccess access = new RecordingAccess(temporaryDirectory, 6);
        DefaultWorldCatalogModel model = new DefaultWorldCatalogModel(
                access,
                executor,
                WorldCatalogStrings.english());
        try {
            CompletableFuture<WorldCatalogSnapshot> ready = nextReadySnapshot(model);
            model.loadIfNeeded();
            ready.get(5, TimeUnit.SECONDS);
            WorldCatalogItem selected = model.load(
                            IndexRange.ofLength(2, 1),
                            new LoadCancellation())
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS)
                    .items()
                    .get(0);

            model.copyWorld(selected, "Copied world").toCompletableFuture().get(5, TimeUnit.SECONDS);
            Path archive = temporaryDirectory.resolve("exported.zip");
            model.exportWorld(selected, archive).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(selected, access.copiedWorld());
            assertEquals("Copied world", access.copyName());
            assertEquals(selected, access.exportedWorld());
            assertEquals(archive.toAbsolutePath().normalize(), access.exportedArchive());
            assertEquals(3, access.indexCalls());
            assertEquals(List.of(selected.path()), access.materializedDirectories());
        } finally {
            model.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Subscribes to the next ready state without retaining the registration after completion.
    ///
    /// @param model model whose next terminal refresh is observed
    /// @return completion for the next ready snapshot
    private static CompletableFuture<WorldCatalogSnapshot> nextReadySnapshot(DefaultWorldCatalogModel model) {
        CompletableFuture<WorldCatalogSnapshot> ready = new CompletableFuture<>();
        Subscription subscription = model.subscribe(change -> {
            @Nullable WorldCatalogSnapshot snapshot = change.currentValue();
            if (snapshot != null && snapshot.status() == WorldCatalogStatus.READY) {
                ready.complete(snapshot);
            }
        });
        ready.whenComplete((snapshot, failure) -> subscription.unsubscribe());
        return ready;
    }

    /// Deterministic blocking source that records shallow indexes and per-range materialization separately.
    @NotNullByDefault
    private static final class RecordingAccess implements WorldCatalogAccess {
        /// Fixed shallow source used by every refresh.
        private final @Unmodifiable List<Path> directories;

        /// Direct-child index invocation count.
        private final AtomicInteger indexCalls = new AtomicInteger();

        /// Ordered directories for which row metadata was requested.
        private final List<Path> materializedDirectories = new ArrayList<>();

        /// World most recently delegated to copy, or null before a copy.
        private volatile @Nullable WorldCatalogItem copiedWorld;

        /// Copy target most recently delegated, or null before a copy.
        private volatile @Nullable String copyName;

        /// World most recently delegated to export, or null before an export.
        private volatile @Nullable WorldCatalogItem exportedWorld;

        /// Export path most recently delegated, or null before an export.
        private volatile @Nullable Path exportedArchive;

        /// Creates a source with deterministic ordered world directory paths.
        ///
        /// @param root absolute temporary root
        /// @param count non-negative source size
        private RecordingAccess(Path root, int count) {
            if (count < 0) {
                throw new IllegalArgumentException("count must not be negative");
            }
            List<Path> createdDirectories = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                createdDirectories.add(root.resolve("world-" + index).toAbsolutePath().normalize());
            }
            directories = List.copyOf(createdDirectories);
        }

        /// Returns a stable fake saves directory without performing any I/O.
        ///
        /// @return parent directory of the indexed fixture paths
        @Override
        public Path savesDirectory() {
            return directories.isEmpty()
                    ? Path.of("saves").toAbsolutePath().normalize()
                    : directories.get(0).getParent();
        }

        /// Records one shallow source index without materializing any row metadata.
        ///
        /// @param cancellation cooperative cancellation signal
        /// @return immutable ordered path source
        @Override
        public @Unmodifiable List<Path> indexWorldDirectories(LoadCancellation cancellation) {
            cancellation.throwIfCancelled();
            indexCalls.incrementAndGet();
            return directories;
        }

        /// Records one requested row and returns synthetic loaded metadata.
        ///
        /// @param directory requested current directory
        /// @param cancellation cooperative cancellation signal
        /// @return non-null synthetic row
        @Override
        public WorldCatalogItem loadItem(Path directory, LoadCancellation cancellation) {
            cancellation.throwIfCancelled();
            Path normalizedDirectory = directory.toAbsolutePath().normalize();
            synchronized (materializedDirectories) {
                materializedDirectories.add(normalizedDirectory);
            }
            return new WorldCatalogItem(
                    normalizedDirectory,
                    normalizedDirectory.getFileName().toString(),
                    normalizedDirectory.getFileName().toString(),
                    0L,
                    null,
                    false,
                    null);
        }

        /// Creates a synthetic candidate for unused import-model contract coverage.
        ///
        /// @param archive selected source archive
        /// @param cancellation cooperative cancellation signal
        /// @return normalized synthetic import candidate
        @Override
        public WorldCatalogImport inspectImport(Path archive, LoadCancellation cancellation) {
            cancellation.throwIfCancelled();
            return new WorldCatalogImport(archive, "world");
        }

        /// Ignores an unused synthetic installation request.
        ///
        /// @param world synthetic candidate
        /// @param targetName requested target name
        /// @param cancellation cooperative cancellation signal
        @Override
        public void install(
                WorldCatalogImport world,
                String targetName,
                LoadCancellation cancellation) throws IOException {
            cancellation.throwIfCancelled();
        }

        /// Ignores an unused synthetic deletion request.
        ///
        /// @param world synthetic row
        /// @param cancellation cooperative cancellation signal
        @Override
        public void delete(WorldCatalogItem world, LoadCancellation cancellation) throws IOException {
            cancellation.throwIfCancelled();
        }

        /// Records one synthetic copy delegation.
        ///
        /// @param world selected synthetic row
        /// @param targetName requested copy name
        /// @param cancellation cooperative cancellation signal
        @Override
        public void copy(
                WorldCatalogItem world,
                String targetName,
                LoadCancellation cancellation) {
            cancellation.throwIfCancelled();
            copiedWorld = world;
            copyName = targetName;
        }

        /// Records one synthetic export delegation.
        ///
        /// @param world selected synthetic row
        /// @param archive requested archive path
        /// @param cancellation cooperative cancellation signal
        @Override
        public void export(
                WorldCatalogItem world,
                Path archive,
                LoadCancellation cancellation) {
            cancellation.throwIfCancelled();
            exportedWorld = world;
            exportedArchive = archive;
        }

        /// Returns the number of shallow-index calls observed so far.
        ///
        /// @return non-negative index call count
        private int indexCalls() {
            return indexCalls.get();
        }

        /// Returns an immutable copy of all metadata materialization requests in call order.
        ///
        /// @return immutable requested directory paths
        private @Unmodifiable List<Path> materializedDirectories() {
            synchronized (materializedDirectories) {
                return List.copyOf(materializedDirectories);
            }
        }

        /// Returns the fixed immutable shallow source.
        ///
        /// @return ordered direct-child paths
        private @Unmodifiable List<Path> directories() {
            return directories;
        }

        /// Returns the most recently copied row.
        ///
        /// @return copied row, or null before copy
        private @Nullable WorldCatalogItem copiedWorld() {
            return copiedWorld;
        }

        /// Returns the most recently requested copy name.
        ///
        /// @return copy name, or null before copy
        private @Nullable String copyName() {
            return copyName;
        }

        /// Returns the most recently exported row.
        ///
        /// @return exported row, or null before export
        private @Nullable WorldCatalogItem exportedWorld() {
            return exportedWorld;
        }

        /// Returns the most recently requested export path.
        ///
        /// @return archive path, or null before export
        private @Nullable Path exportedArchive() {
            return exportedArchive;
        }
    }
}
