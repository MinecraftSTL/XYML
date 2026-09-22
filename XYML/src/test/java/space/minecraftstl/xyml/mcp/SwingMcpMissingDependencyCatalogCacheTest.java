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
import space.minecraftstl.xyml.game.analyzer.RepairTaskPhase;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogBackend;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogItem;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogKind;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogPage;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogQuery;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogSource;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            CompletionStage<RemoteAddonCatalogPage> first = cache.prefetch(query);
            CompletionStage<RemoteAddonCatalogPage> second = cache.prefetch(query);

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

    /// Reuses exact version-scoped prefetches and opens only the dependency selected from all cached choices.
    @Test
    void explicitSearchConsumesCacheAndOpensOnlySelectedDependency() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        RecordingBackend backend = new RecordingBackend(searches, false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<String> openedDependencies = new ArrayList<>();
        AtomicReference<List<SwingMcpMissingDependencySearch.DependencyChoice>> presentedChoices =
                new AtomicReference<>();
        SwingMcpMissingDependencySearch search = SwingMcpMissingDependencySearch.forSearchAction(
                openedDependencies::add,
                choices -> {
                    presentedChoices.set(List.copyOf(choices));
                    return choices.get(1);
                },
                backend,
                executor);
        try {
            @Unmodifiable List<String> dependencyIds = List.of("fabric-api", "cloth-config");
            search.prefetch(dependencyIds, "1.20.1");
            waitForSearches(searches, 2);

            Task<?> task = search.createTask(dependencyIds, "1.20.1");
            assertTrue(openedDependencies.isEmpty());
            task.execute();

            assertEquals(2, searches.get());
            assertEquals(List.of("cloth-config"), openedDependencies);
            @Unmodifiable List<SwingMcpMissingDependencySearch.DependencyChoice> choices =
                    presentedChoices.get();
            assertEquals(dependencyIds, choices.stream()
                    .map(SwingMcpMissingDependencySearch.DependencyChoice::dependencyId)
                    .toList());
            assertTrue(choices.stream().allMatch(choice -> choice.candidateLabels().size() == 1));
        } finally {
            search.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Treats a dependency chooser cancellation as terminal for that attempt without opening a result page.
    @Test
    void cancelledDependencySelectionDoesNotNavigate() {
        AtomicInteger searches = new AtomicInteger();
        RecordingBackend backend = new RecordingBackend(searches, false);
        List<String> openedDependencies = new ArrayList<>();
        SwingMcpMissingDependencySearch search = SwingMcpMissingDependencySearch.forSearchAction(
                openedDependencies::add,
                choices -> null,
                backend,
                Runnable::run);
        try {
            Task<?> task = search.createTask(List.of("fabric-api"), "1.20.1");

            assertThrows(CancellationException.class, task::execute);
            assertEquals(1, searches.get());
            assertTrue(openedDependencies.isEmpty());
        } finally {
            search.close();
        }
    }

    /// Publishes the awaiting-selection phase for the entire lifetime of a blocking dependency chooser.
    @Test
    void searchTaskPublishesLiveSelectionPhase() throws Exception {
        CountDownLatch selectionActive = new CountDownLatch(1);
        CountDownLatch releaseSelection = new CountDownLatch(1);
        List<String> openedDependencies = new ArrayList<>();
        ExecutorService taskExecutor = Executors.newSingleThreadExecutor();
        SwingMcpMissingDependencySearch search = SwingMcpMissingDependencySearch.forSearchAction(
                openedDependencies::add,
                choices -> {
                    selectionActive.countDown();
                    awaitLatchUnchecked(releaseSelection);
                    return choices.get(0);
                },
                new RecordingBackend(new AtomicInteger(), false),
                Runnable::run);
        try {
            Task<?> task = search.createTask(List.of("fabric-api"), "1.20.1");
            Future<Throwable> outcome = taskExecutor.submit(() -> executeForOutcome(task));

            assertTrue(selectionActive.await(5L, TimeUnit.SECONDS));
            assertEquals(
                    RepairTaskPhase.AWAITING_SELECTION,
                    task.getProperties().get(RepairTaskPhase.TASK_PROPERTY));

            releaseSelection.countDown();
            assertNull(outcome.get(5L, TimeUnit.SECONDS));
            assertEquals(RepairTaskPhase.RUNNING, task.getProperties().get(RepairTaskPhase.TASK_PROPERTY));
            assertEquals(List.of("fabric-api"), openedDependencies);
        } finally {
            releaseSelection.countDown();
            search.close();
            taskExecutor.shutdownNow();
            assertTrue(taskExecutor.awaitTermination(5L, TimeUnit.SECONDS));
        }
    }

    /// Waits for an already-entered selector before close returns and suppresses its subsequent navigation.
    @Test
    void closeWaitsForClaimedSelectorAndPreventsNavigation() throws Exception {
        CountDownLatch selectionActive = new CountDownLatch(1);
        CountDownLatch releaseSelection = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        List<String> openedDependencies = new ArrayList<>();
        ExecutorService taskExecutor = Executors.newSingleThreadExecutor();
        ExecutorService closeExecutor = Executors.newFixedThreadPool(2);
        SwingMcpMissingDependencySearch search = SwingMcpMissingDependencySearch.forSearchAction(
                openedDependencies::add,
                choices -> {
                    selectionActive.countDown();
                    awaitLatchUnchecked(releaseSelection);
                    return choices.get(0);
                },
                new RecordingBackend(new AtomicInteger(), false),
                Runnable::run);
        try {
            Task<?> task = search.createTask(List.of("fabric-api"), "1.20.1");
            Future<Throwable> taskOutcome = taskExecutor.submit(() -> executeForOutcome(task));
            assertTrue(selectionActive.await(5L, TimeUnit.SECONDS));
            Future<?> closeOutcome = closeExecutor.submit(() -> {
                closeStarted.countDown();
                search.close();
            });
            assertTrue(closeStarted.await(5L, TimeUnit.SECONDS));

            assertThrows(TimeoutException.class, () -> closeOutcome.get(200L, TimeUnit.MILLISECONDS));
            assertFalse(closeOutcome.isDone());
            Future<?> concurrentCloseOutcome = closeExecutor.submit(search::close);
            assertThrows(TimeoutException.class, () -> concurrentCloseOutcome.get(200L, TimeUnit.MILLISECONDS));
            assertFalse(concurrentCloseOutcome.isDone());
            releaseSelection.countDown();

            closeOutcome.get(5L, TimeUnit.SECONDS);
            concurrentCloseOutcome.get(5L, TimeUnit.SECONDS);
            assertTrue(taskOutcome.get(5L, TimeUnit.SECONDS) instanceof CancellationException);
            assertTrue(openedDependencies.isEmpty());
        } finally {
            releaseSelection.countDown();
            search.close();
            taskExecutor.shutdownNow();
            closeExecutor.shutdownNow();
            assertTrue(taskExecutor.awaitTermination(5L, TimeUnit.SECONDS));
            assertTrue(closeExecutor.awaitTermination(5L, TimeUnit.SECONDS));
        }
    }

    /// Limits completed and in-flight entries together without submitting work beyond capacity.
    @Test
    void boundsCombinedCompletedAndInFlightEntries() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        RecordingBackend backend = new RecordingBackend(searches, false);
        List<Runnable> scheduled = new ArrayList<>();
        try (SwingMcpMissingDependencyCatalogCache cache =
                new SwingMcpMissingDependencyCatalogCache(backend, scheduled::add, 2)) {
            CompletionStage<RemoteAddonCatalogPage> first =
                    cache.prefetch(query("first", "1.20.1"));
            CompletionStage<RemoteAddonCatalogPage> second =
                    cache.prefetch(query("second", "1.20.1"));
            CompletionStage<RemoteAddonCatalogPage> rejected =
                    cache.prefetch(query("third", "1.20.1"));

            assertEquals(2, scheduled.size());
            assertTrue(rejected.toCompletableFuture().handle((ignored, failure) -> failure != null)
                    .get(5, TimeUnit.SECONDS));
            scheduled.forEach(Runnable::run);
            first.toCompletableFuture().get(5, TimeUnit.SECONDS);
            second.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(2, searches.get());
        }
    }

    /// Cancels a search closed during an in-flight prefetch before either selection or navigation can run.
    @Test
    void closeDuringPrefetchSuppressesSelectorAndNavigation() throws Exception {
        CountDownLatch backendStarted = new CountDownLatch(1);
        CountDownLatch releaseBackend = new CountDownLatch(1);
        AtomicInteger selectorCalls = new AtomicInteger();
        List<String> openedDependencies = new ArrayList<>();
        ExecutorService providerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService taskExecutor = Executors.newSingleThreadExecutor();
        SwingMcpMissingDependencySearch search = SwingMcpMissingDependencySearch.forSearchAction(
                openedDependencies::add,
                choices -> {
                    selectorCalls.incrementAndGet();
                    return choices.get(0);
                },
                new BlockingBackend(backendStarted, releaseBackend),
                providerExecutor);
        try {
            Task<?> task = search.createTask(List.of("fabric-api"), "1.20.1");
            Future<Throwable> outcome = taskExecutor.submit(() -> {
                try {
                    task.execute();
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertTrue(backendStarted.await(5, TimeUnit.SECONDS));

            search.close();
            Throwable failure = outcome.get(5, TimeUnit.SECONDS);

            assertTrue(failure instanceof CancellationException);
            assertEquals(0, selectorCalls.get());
            assertTrue(openedDependencies.isEmpty());
        } finally {
            search.close();
            releaseBackend.countDown();
            providerExecutor.shutdownNow();
            taskExecutor.shutdownNow();
            assertTrue(providerExecutor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(taskExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Stops waiting for a shared provider request promptly after ordinary task-executor cancellation.
    @Test
    void executorCancellationStopsProviderWaitWithoutCancellingSharedPrefetch() throws Exception {
        CountDownLatch backendStarted = new CountDownLatch(1);
        CountDownLatch releaseBackend = new CountDownLatch(1);
        CountDownLatch taskStopped = new CountDownLatch(1);
        AtomicInteger selectorCalls = new AtomicInteger();
        List<String> openedDependencies = new ArrayList<>();
        ExecutorService providerExecutor = Executors.newSingleThreadExecutor();
        SwingMcpMissingDependencySearch search = SwingMcpMissingDependencySearch.forSearchAction(
                openedDependencies::add,
                choices -> {
                    selectorCalls.incrementAndGet();
                    return choices.get(0);
                },
                new BlockingBackend(backendStarted, releaseBackend),
                providerExecutor);
        try {
            TaskExecutor executor = search.createTask(List.of("fabric-api"), "1.20.1").executor();
            executor.subscribeTaskListener(new TaskListener() {
                /// Signals the terminal executor callback under test.
                @Override
                public void onStop(boolean success, TaskExecutor completedExecutor) {
                    taskStopped.countDown();
                }
            });
            executor.start();
            assertTrue(backendStarted.await(5L, TimeUnit.SECONDS));

            executor.cancel();

            assertTrue(taskStopped.await(2L, TimeUnit.SECONDS));
            assertTrue(executor.isCancelled());
            assertEquals(0, selectorCalls.get());
            assertTrue(openedDependencies.isEmpty());
        } finally {
            releaseBackend.countDown();
            search.close();
            providerExecutor.shutdownNow();
            assertTrue(providerExecutor.awaitTermination(5L, TimeUnit.SECONDS));
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

    /// Executes one task and returns its terminal failure for concurrent lifecycle assertions.
    ///
    /// @param task task to execute
    /// @return thrown failure, or null after success
    private static Throwable executeForOutcome(Task<?> task) {
        try {
            task.execute();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    /// Waits for a fixture latch and converts interruption or timeout into an unchecked test failure.
    ///
    /// @param latch fixture gate
    private static void awaitLatchUnchecked(CountDownLatch latch) {
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test latch", exception);
        }
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

    /// Read-only backend that keeps one provider request in flight until its test releases it.
    @NotNullByDefault
    private static final class BlockingBackend implements RemoteAddonCatalogBackend {
        /// Signals that provider search started.
        private final CountDownLatch started;

        /// Allows the provider search to return.
        private final CountDownLatch release;

        /// Creates one blocking provider fixture.
        ///
        /// @param started start signal
        /// @param release completion gate
        private BlockingBackend(CountDownLatch started, CountDownLatch release) {
            this.started = started;
            this.release = release;
        }

        /// Returns no category metadata.
        @Override
        public @Unmodifiable List<RemoteAddonRepository.Category> loadCategories(
                RemoteAddonCatalogKind kind,
                RemoteAddonCatalogSource source) {
            return List.of();
        }

        /// Blocks one read-only lookup until the test closes the owning adapter.
        @Override
        public RemoteAddonCatalogPage search(RemoteAddonCatalogQuery query) throws IOException {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting for provider release");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Provider lookup was interrupted", exception);
            }
            return new RemoteAddonCatalogPage(List.of(), query.pageOffset(), 0);
        }

        /// Returns no versions for this search-only fixture.
        @Override
        public @Unmodifiable List<RemoteAddon.Version> loadVersions(RemoteAddonCatalogItem item) {
            return List.of();
        }

        /// Returns an inert page URL for the unused version boundary.
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
