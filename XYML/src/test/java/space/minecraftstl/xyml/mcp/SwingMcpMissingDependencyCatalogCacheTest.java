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
package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogBackend;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogItem;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogKind;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogPage;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogQuery;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogSource;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies bounded read-only dependency catalog prefetch and retry behavior.
@NotNullByDefault
final class SwingMcpMissingDependencyCatalogCacheTest {
    /// Deduplicates concurrent requests and reuses a successful immutable page without opening the Swing UI.
    @Test
    void deduplicatesAndCachesReadOnlyPrefetch() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        RecordingBackend backend = new RecordingBackend(searches, false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (SwingMcpMissingDependencyCatalogCache cache =
                new SwingMcpMissingDependencyCatalogCache(backend, executor, 2)) {
            RemoteAddonCatalogQuery query = query("fabric-api", "1.20.1");
            java.util.concurrent.CompletionStage<RemoteAddonCatalogPage> first = cache.prefetch(query);
            java.util.concurrent.CompletionStage<RemoteAddonCatalogPage> second = cache.prefetch(query);

            assertSame(first, second);
            // The executor may run the first request before this assertion; the cache contract is one provider call,
            // not a particular scheduling delay.
            assertTrue(searches.get() <= 1);
            RemoteAddonCatalogPage page = first.toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(1, searches.get());
            assertEquals(query.pageOffset(), page.pageOffset());
            assertEquals(Optional.of(page), cache.get(query));
            assertEquals(page, cache.prefetch(query).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(1, searches.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Removes failed requests so a later invocation can retry the provider operation.
    @Test
    void removesFailedRequestAndAllowsRetry() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        RecordingBackend backend = new RecordingBackend(searches, true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (SwingMcpMissingDependencyCatalogCache cache =
                new SwingMcpMissingDependencyCatalogCache(backend, executor)) {
            RemoteAddonCatalogQuery query = query("cloth-config", "1.20.1");
            assertTrue(cache.prefetch(query).toCompletableFuture().handle((ignored, failure) -> failure != null)
                    .get(5, TimeUnit.SECONDS));
            assertEquals(0, cache.size());

            RemoteAddonCatalogPage retried = cache.prefetch(query).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(query.pageOffset(), retried.pageOffset());
            assertEquals(2, searches.get());
            assertTrue(cache.get(query).isPresent());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Prefetches every dependency through the existing backend while keeping the explicit search task as the only UI
    /// navigation command.
    @Test
    void adapterPrefetchesCandidatesWithoutOpeningOrWriting() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        RecordingBackend backend = new RecordingBackend(searches, false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RecordingSearchRuntime runtime = new RecordingSearchRuntime();
        SwingMcpMissingDependencySearch search = SwingMcpMissingDependencySearch.forRuntimeResolver(
                () -> runtime,
                () -> false,
                backend,
                executor);
        try {
            search.prefetch(List.of("fabric-api", "cloth-config"), "1.20.1");
            waitForSearches(searches, 2);

            assertEquals(2, searches.get());
            assertEquals(List.of("fabric-api", "cloth-config"), backend.queries().stream()
                    .map(RemoteAddonCatalogQuery::searchText)
                    .toList());
            assertEquals(1, search.cachedCandidates("fabric-api", "1.20.1").size());
            assertTrue(runtime.searches().isEmpty());

            assertNotSame(search.createTask(List.of("fabric-api")), search.createTask(List.of("fabric-api")));
            search.createTask(List.of("fabric-api")).execute();
            assertEquals(List.of("fabric-api"), runtime.searches());
        } finally {
            search.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Builds one bounded Modrinth MOD query used by the cache tests.
    private static RemoteAddonCatalogQuery query(String id, String gameVersion) {
        return new RemoteAddonCatalogQuery(
                RemoteAddonCatalogKind.MOD,
                RemoteAddonCatalogSource.MODRINTH,
                id,
                gameVersion,
                null,
                RemoteAddonRepository.SortType.POPULARITY,
                0,
                SwingMcpMissingDependencyCatalogCache.DEFAULT_PAGE_SIZE);
    }

    /// Waits for the injected single-thread provider executor without sleeping in a polling loop.
    private static void waitForSearches(AtomicInteger searches, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (searches.get() < expected && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(expected, searches.get());
    }

    /// Minimal read-only backend that returns one deterministic candidate page.
    @NotNullByDefault
    private static final class RecordingBackend implements RemoteAddonCatalogBackend {
        /// Number of provider search calls.
        private final AtomicInteger searches;

        /// Whether the first search should fail.
        private final AtomicBoolean failFirst;

        /// Queries observed by the backend.
        private final java.util.concurrent.CopyOnWriteArrayList<RemoteAddonCatalogQuery> queries =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        /// Creates a deterministic backend.
        ///
        /// @param searches shared search counter
        /// @param failFirst whether the first request fails
        private RecordingBackend(AtomicInteger searches, boolean failFirst) {
            this.searches = searches;
            this.failFirst = new AtomicBoolean(failFirst);
        }

        /// Returns no category metadata because prefetch must only call project search.
        @Override
        public @Unmodifiable List<RemoteAddonRepository.Category> loadCategories(
                RemoteAddonCatalogKind kind,
                RemoteAddonCatalogSource source) {
            return List.of();
        }

        /// Records and resolves one deterministic candidate page.
        @Override
        public RemoteAddonCatalogPage search(RemoteAddonCatalogQuery query) throws IOException {
            queries.add(query);
            int count = searches.incrementAndGet();
            if (failFirst.compareAndSet(true, false)) {
                throw new IOException("synthetic provider failure " + count);
            }
            return new RemoteAddonCatalogPage(
                    List.of(new RemoteAddonCatalogItem(
                            RemoteAddon.BROKEN,
                            RemoteAddonCatalogKind.MOD,
                            RemoteAddonCatalogSource.MODRINTH)),
                    query.pageOffset(),
                    1);
        }

        /// Returns immutable backend query history.
        private @Unmodifiable List<RemoteAddonCatalogQuery> queries() {
            return List.copyOf(queries);
        }

        /// Unused version-loading boundary.
        @Override
        public @Unmodifiable List<RemoteAddon.Version> loadVersions(RemoteAddonCatalogItem item) {
            return List.of();
        }

        /// Unused version-page boundary.
        @Override
        public URI versionPage(RemoteAddonCatalogItem item, RemoteAddon.Version version) {
            return URI.create("https://example.invalid/version");
        }
    }

    /// Minimal runtime that records only explicit UI search commands.
    @NotNullByDefault
    private static final class RecordingSearchRuntime implements SwingMcpMissingDependencySearch.SearchRuntime {
        /// Explicitly opened dependency searches.
        private final java.util.concurrent.CopyOnWriteArrayList<String> searches =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        /// Reports an always-open test runtime.
        @Override
        public boolean isClosed() {
            return false;
        }

        /// Records one explicit search command.
        @Override
        public void openModSearch(String dependencyId) {
            searches.add(dependencyId);
        }

        /// Returns an immutable search history.
        private @Unmodifiable List<String> searches() {
            return List.copyOf(searches);
        }
    }
}
