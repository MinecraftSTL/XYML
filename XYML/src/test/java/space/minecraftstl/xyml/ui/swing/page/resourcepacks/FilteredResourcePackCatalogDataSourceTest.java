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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies shallow resource-pack filtering and exact mapped viewport loading.
@NotNullByDefault
final class FilteredResourcePackCatalogDataSourceTest {
    /// Filters legacy file-name queries and loads only matching source-index segments.
    @Test
    void filtersFileNamesWithoutWideningDelegateViewportLoads() {
        ResourcePackCatalogItem alphaOne = item("Alpha-One.zip");
        ResourcePackCatalogItem beta = item("Beta.zip");
        ResourcePackCatalogItem alphaTwo = item("alpha-two.zip");
        RecordingModel model = new RecordingModel(List.of(alphaOne, beta, alphaTwo));
        FilteredResourcePackCatalogDataSource source = new FilteredResourcePackCatalogDataSource(model);

        assertTrue(source.setSearchQuery("alpha"));
        assertEquals(OptionalInt.of(2), source.exactItemCount());
        assertEquals(
                List.of(alphaOne.path(), alphaTwo.path()),
                source.selectedPaths(new int[] {0, 1}));

        ChoicePage<ResourcePackCatalogItem> page = source.load(
                new IndexRange(0, 2),
                new LoadCancellation()).toCompletableFuture().join();
        assertEquals(List.of(alphaOne, alphaTwo), page.items());
        assertEquals(List.of(new IndexRange(0, 1), new IndexRange(2, 3)), model.requestedRanges());
        assertFalse(model.loadedPaths().contains(beta.path()));

        assertTrue(source.setSearchQuery("regex:["));
        assertEquals(OptionalInt.of(0), source.exactItemCount());
        assertFalse(source.setSearchQuery("regex:["));
    }

    /// Creates one deterministic presentation row.
    ///
    /// @param fileName shallow indexed file name
    /// @return immutable fixture row
    private static ResourcePackCatalogItem item(String fileName) {
        Path path = Path.of("resourcepacks", fileName).toAbsolutePath().normalize();
        return new ResourcePackCatalogItem(
                path,
                fileName,
                fileName,
                "fixture",
                ResourcePackCompatibility.COMPATIBLE,
                false);
    }

    /// Exact in-memory delegate that records every viewport range and loaded path.
    @NotNullByDefault
    private static final class RecordingModel implements ResourcePackCatalogModel {
        /// Immutable source rows.
        private final @Unmodifiable List<ResourcePackCatalogItem> rows;

        /// Exact delegate ranges requested by the filtered source.
        private final List<IndexRange> requestedRanges = new ArrayList<>();

        /// Paths whose metadata rows were actually returned.
        private final List<Path> loadedPaths = new ArrayList<>();

        /// Ready snapshot matching the immutable source rows.
        private final ResourcePackCatalogSnapshot snapshot;

        /// Creates one exact ready delegate.
        ///
        /// @param rows immutable source rows
        private RecordingModel(@Unmodifiable List<ResourcePackCatalogItem> rows) {
            this.rows = List.copyOf(rows);
            snapshot = new ResourcePackCatalogSnapshot(
                    OptionalInt.empty(),
                    OptionalInt.of(rows.size()),
                    1L,
                    ResourcePackCatalogStatus.READY,
                    "ready",
                    ResourcePackCatalogWriteStatus.IDLE,
                    "",
                    !rows.isEmpty(),
                    true);
        }

        /// Returns the stable ready snapshot.
        @Override
        public ResourcePackCatalogSnapshot snapshot() {
            return snapshot;
        }

        /// Returns immutable shallow paths in source order.
        @Override
        public @Unmodifiable List<Path> indexedPaths() {
            return rows.stream().map(ResourcePackCatalogItem::path).toList();
        }

        /// Returns a no-op listener registration.
        @Override
        public Subscription subscribe(ValueChangeListener<ResourcePackCatalogSnapshot> listener) {
            Objects.requireNonNull(listener, "listener");
            return Subscription.create(() -> { });
        }

        /// Returns the exact source count.
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(rows.size());
        }

        /// Returns the stable source revision.
        @Override
        public OptionalLong sourceRevision() {
            return OptionalLong.of(1L);
        }

        /// Records and returns one exact source range.
        @Override
        public CompletionStage<ChoicePage<ResourcePackCatalogItem>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            cancellation.throwIfCancelled();
            IndexRange actual = desiredRange.clampToItemCount(rows.size());
            @Unmodifiable List<ResourcePackCatalogItem> loaded = List.copyOf(rows.subList(
                    actual.startInclusive(),
                    actual.endExclusive()));
            requestedRanges.add(actual);
            loaded.stream().map(ResourcePackCatalogItem::path).forEach(loadedPaths::add);
            return CompletableFuture.completedFuture(new ChoicePage<>(
                    actual,
                    loaded,
                    OptionalInt.of(rows.size()),
                    actual.endExclusive() == rows.size()));
        }

        /// Leaves the already ready fixture unchanged.
        @Override
        public void loadIfNeeded() {
        }

        /// Leaves the immutable fixture unchanged.
        @Override
        public void refresh() {
        }

        /// Accepts no-op fixture selection.
        @Override
        public void selectResourcePack(Path path) {
            Objects.requireNonNull(path, "path");
        }

        /// Accepts no-op fixture selection clearing.
        @Override
        public void clearSelection() {
        }

        /// Returns the unchanged snapshot for an unused import.
        @Override
        public CompletionStage<ResourcePackCatalogSnapshot> importResourcePacks(List<Path> sources) {
            Objects.requireNonNull(sources, "sources");
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Returns the unchanged snapshot for an unused enable.
        @Override
        public CompletionStage<ResourcePackCatalogSnapshot> enableResourcePack(Path path) {
            Objects.requireNonNull(path, "path");
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Returns the unchanged snapshot for an unused disable.
        @Override
        public CompletionStage<ResourcePackCatalogSnapshot> disableResourcePack(Path path) {
            Objects.requireNonNull(path, "path");
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Returns the unchanged snapshot for an unused deletion.
        @Override
        public CompletionStage<ResourcePackCatalogSnapshot> deleteResourcePack(Path path) {
            Objects.requireNonNull(path, "path");
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Leaves the in-memory fixture unchanged.
        @Override
        public void close() {
        }

        /// Returns immutable requested ranges.
        ///
        /// @return requested ranges
        private @Unmodifiable List<IndexRange> requestedRanges() {
            return List.copyOf(requestedRanges);
        }

        /// Returns immutable loaded paths.
        ///
        /// @return loaded paths
        private @Unmodifiable List<Path> loadedPaths() {
            return List.copyOf(loadedPaths);
        }
    }
}
