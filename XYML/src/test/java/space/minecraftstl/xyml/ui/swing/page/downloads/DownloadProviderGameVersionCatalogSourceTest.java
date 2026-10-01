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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import space.minecraftstl.xyml.download.ComponentRemoteVersion;
import space.minecraftstl.xyml.download.ComponentRemoteVersionList;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.TestComponentRemoteVersion;
import space.minecraftstl.xyml.download.game.GameRemoteVersion;
import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.game.ReleaseType;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Verifies provider task ownership, serialized refreshes, mapping, and cancellation of the game catalog source.
@NotNullByDefault
final class DownloadProviderGameVersionCatalogSourceTest {
    /// Maps one fetched game list into newest-first unique catalog items with stable classifications.
    @Test
    @Timeout(10)
    void mapsTheFetchedGameListIntoUniqueSortedItems() throws Exception {
        Instant newest = Instant.parse("2025-11-01T00:00:00Z");
        RecordingDownloadProvider provider = new RecordingDownloadProvider(List.of(
                gameVersion("1.21.1", ReleaseType.RELEASE, Instant.parse("2024-08-08T00:00:00Z")),
                gameVersion("25w21a", ReleaseType.SNAPSHOT, Instant.parse("2025-05-22T00:00:00Z")),
                gameVersion("24w14potato", ReleaseType.SNAPSHOT, Instant.parse("2024-04-01T00:00:00Z")),
                gameVersion("1.21-pre1", ReleaseType.PENDING, Instant.parse("2024-05-01T00:00:00Z")),
                gameVersion("25w45a_unobfuscated", ReleaseType.UNOBFUSCATED, newest),
                gameVersion("unknown-build", ReleaseType.UNKNOWN, Instant.parse("2023-01-01T00:00:00Z")),
                gameVersion("b1.7.3", ReleaseType.OLD_BETA, Instant.parse("2011-07-08T00:00:00Z")),
                gameVersion("a1.2.6", ReleaseType.OLD_ALPHA, Instant.parse("2010-10-30T00:00:00Z")),
                gameVersion("1.21.1", ReleaseType.SNAPSHOT, Instant.parse("2020-01-01T00:00:00Z"))));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();

        try (DownloadProviderGameVersionCatalogSource source =
                     new DownloadProviderGameVersionCatalogSource(provider, executors)) {
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage = source.load(new LoadCancellation());
            assertEquals(1, executors.executorCount());
            executors.executor(0).finish(true, null);

            @Unmodifiable List<GameVersionCatalogItem> items = await(stage);
            assertEquals(List.of("game"), provider.requestedTypes());
            assertEquals(
                    List.of(
                            "25w45a_unobfuscated",
                            "25w21a",
                            "1.21.1",
                            "1.21-pre1",
                            "24w14potato",
                            "unknown-build",
                            "b1.7.3",
                            "a1.2.6"),
                    items.stream().map(GameVersionCatalogItem::versionId).toList());
            assertEquals(GameVersionKind.APRIL_FOOLS, kindOf(items, "24w14potato"));
            assertEquals(GameVersionKind.RELEASE, kindOf(items, "1.21.1"));
            assertEquals(GameVersionKind.SNAPSHOT, kindOf(items, "1.21-pre1"));
            assertEquals(GameVersionKind.OLD, kindOf(items, "unknown-build"));
            assertEquals(GameVersionKind.OLD, kindOf(items, "b1.7.3"));
            assertEquals(Optional.of(newest), itemOf(items, "25w45a_unobfuscated").releaseDate());
        }
    }

    /// Starts a superseding request only after the running task terminates and keeps the latest result.
    @Test
    @Timeout(10)
    void serializesSupersededRequests() throws Exception {
        RecordingDownloadProvider provider =
                new RecordingDownloadProvider(List.of(gameVersion("1.21.1", ReleaseType.RELEASE, Instant.EPOCH)));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();

        try (DownloadProviderGameVersionCatalogSource source =
                     new DownloadProviderGameVersionCatalogSource(provider, executors)) {
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> superseded =
                    source.load(new LoadCancellation());
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> latest =
                    source.load(new LoadCancellation());
            assertEquals(1, executors.executorCount(), "A pending request must not start on its own");

            executors.executor(0).finish(true, null);
            assertEquals(2, executors.executorCount(), "The latest request starts after the first terminates");
            executors.executor(1).finish(true, null);

            assertCancelled(superseded);
            assertEquals(List.of("1.21.1"), await(latest).stream()
                    .map(GameVersionCatalogItem::versionId)
                    .toList());
        }
    }

    /// Rejects a pre-cancelled request without invoking the configured provider.
    @Test
    void rejectsPreCancelledLoads() {
        RecordingDownloadProvider provider =
                new RecordingDownloadProvider(List.of(gameVersion("1.21.1", ReleaseType.RELEASE, Instant.EPOCH)));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        LoadCancellation cancellation = new LoadCancellation();
        cancellation.cancel();

        try (DownloadProviderGameVersionCatalogSource source =
                     new DownloadProviderGameVersionCatalogSource(provider, executors)) {
            assertCancelled(source.load(cancellation));
            assertEquals(List.of(), provider.requestedTypes());
            assertEquals(0, executors.executorCount());
        }
    }

    /// Cancels the running task on close and rejects every later request.
    @Test
    @Timeout(10)
    void closeCancelsTheActiveTaskAndRejectsLaterLoads() {
        RecordingDownloadProvider provider =
                new RecordingDownloadProvider(List.of(gameVersion("1.21.1", ReleaseType.RELEASE, Instant.EPOCH)));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        DownloadProviderGameVersionCatalogSource source =
                new DownloadProviderGameVersionCatalogSource(provider, executors);

        CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage = source.load(new LoadCancellation());
        source.close();

        assertEquals(1, executors.executor(0).cancelCount());
        assertCancelled(stage);
        assertThrows(IllegalStateException.class, () -> source.load(new LoadCancellation()));
    }

    /// Discards the fetched result when the caller cancels while the task is running.
    @Test
    @Timeout(10)
    void cancellationDuringRefreshDiscardsTheResult() {
        RecordingDownloadProvider provider =
                new RecordingDownloadProvider(List.of(gameVersion("1.21.1", ReleaseType.RELEASE, Instant.EPOCH)));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();

        try (DownloadProviderGameVersionCatalogSource source =
                     new DownloadProviderGameVersionCatalogSource(provider, executors)) {
            LoadCancellation cancellation = new LoadCancellation();
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage = source.load(cancellation);
            cancellation.cancel();
            executors.executor(0).finish(true, null);

            assertCancelled(stage);
        }
    }

    /// Publishes one exact task failure to the caller without replacing its identity.
    @Test
    @Timeout(10)
    void preservesTaskFailureIdentity() {
        RecordingDownloadProvider provider =
                new RecordingDownloadProvider(List.of(gameVersion("1.21.1", ReleaseType.RELEASE, Instant.EPOCH)));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        IllegalStateException failure = new IllegalStateException("terminal failure");

        try (DownloadProviderGameVersionCatalogSource source =
                     new DownloadProviderGameVersionCatalogSource(provider, executors)) {
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage = source.load(new LoadCancellation());
            executors.executor(0).finish(false, failure);

            CompletionException completionFailure = assertThrows(
                    CompletionException.class,
                    () -> stage.toCompletableFuture().join());
            assertSame(failure, completionFailure.getCause());
        }
    }

    /// Fails the request when the configured provider rejects the request synchronously.
    @Test
    void failsTheStageWhenTheProviderThrows() {
        IllegalStateException failure = new IllegalStateException("provider rejected the request");
        DownloadProvider provider = new DownloadProvider() {
            /// Rejects every game catalog request with the configured failure.
            ///
            /// @param type        requested component type
            /// @param gameVersion requested game version, or null for the game catalog
            /// @param refresh     whether a refresh was requested
            /// @return never returns normally
            @Override
            public @Unmodifiable Task<ComponentRemoteVersionList<?>> getVersionsAsync(
                    GameComponentType type, @Nullable GameVersionNumber gameVersion, boolean refresh) {
                throw failure;
            }
        };
        ControlledExecutorFactory executors = new ControlledExecutorFactory();

        try (DownloadProviderGameVersionCatalogSource source =
                     new DownloadProviderGameVersionCatalogSource(provider, executors)) {
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage = source.load(new LoadCancellation());

            CompletionException completionFailure = assertThrows(
                    CompletionException.class,
                    () -> stage.toCompletableFuture().join());
            assertSame(failure, completionFailure.getCause());
            assertEquals(0, executors.executorCount());
        }
    }

    /// Rejects a fetched entry that is not a concrete Minecraft game version.
    @Test
    @Timeout(10)
    void rejectsUnexpectedRemoteVersionType() {
        RecordingDownloadProvider provider = new RecordingDownloadProvider(List.of(
                new TestComponentRemoteVersion(GameComponentType.FORGE, "1.21.1", "47.2.0")));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();

        try (DownloadProviderGameVersionCatalogSource source =
                     new DownloadProviderGameVersionCatalogSource(provider, executors)) {
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage = source.load(new LoadCancellation());
            executors.executor(0).finish(true, null);

            CompletionException completionFailure = assertThrows(
                    CompletionException.class,
                    () -> stage.toCompletableFuture().join());
            IllegalStateException cause =
                    assertInstanceOf(IllegalStateException.class, completionFailure.getCause());
            assertTrue(cause.getMessage().contains(ComponentRemoteVersion.class.getSimpleName()));
        }
    }

    /// Fails the affected request when the executor cannot start and allows a later retry.
    @Test
    @Timeout(10)
    void executorStartFailureFailsTheRequestAndAllowsRetry() throws Exception {
        RecordingDownloadProvider provider =
                new RecordingDownloadProvider(List.of(gameVersion("1.21.1", ReleaseType.RELEASE, Instant.EPOCH)));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        RuntimeException startFailure = new RuntimeException("start rejected");
        executors.failNextStart(startFailure);

        try (DownloadProviderGameVersionCatalogSource source =
                     new DownloadProviderGameVersionCatalogSource(provider, executors)) {
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> failed = source.load(new LoadCancellation());
            CompletionException completionFailure = assertThrows(
                    CompletionException.class,
                    () -> failed.toCompletableFuture().join());
            assertSame(startFailure, completionFailure.getCause());

            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> retried = source.load(new LoadCancellation());
            executors.executor(1).finish(true, null);
            assertEquals(List.of("1.21.1"), await(retried).stream()
                    .map(GameVersionCatalogItem::versionId)
                    .toList());
        }
    }

    /// Creates one core game-version row with explicit nullable release date handling.
    ///
    /// @param versionId   game version identifier
    /// @param type        upstream release type
    /// @param releaseDate upstream date, or null when the manifest has none
    /// @return core remote game version
    private static GameRemoteVersion gameVersion(
            String versionId,
            ReleaseType type,
            @Nullable Instant releaseDate) {
        return new GameRemoteVersion(
                GameVersionNumber.asGameVersion(versionId),
                List.of("https://example.invalid/" + versionId),
                type,
                releaseDate);
    }

    /// Finds one mapped item by its stable version identifier.
    ///
    /// @param items     mapped catalog
    /// @param versionId requested identifier
    /// @return matching item
    private static GameVersionCatalogItem itemOf(
            @Unmodifiable List<GameVersionCatalogItem> items,
            String versionId) {
        for (GameVersionCatalogItem item : items) {
            if (item.versionId().equals(versionId)) {
                return item;
            }
        }
        throw new AssertionError("Missing catalog item: " + versionId);
    }

    /// Returns the classification of one mapped item.
    ///
    /// @param items     mapped catalog
    /// @param versionId requested identifier
    /// @return mapped classification
    private static GameVersionKind kindOf(
            @Unmodifiable List<GameVersionCatalogItem> items,
            String versionId) {
        return itemOf(items, versionId).kind();
    }

    /// Waits for one successful stage with a bounded timeout.
    ///
    /// @param stage stage to await
    /// @return completed immutable catalog
    private static @Unmodifiable List<GameVersionCatalogItem> await(
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /// Asserts direct or wrapped cancellation of one catalog stage.
    ///
    /// @param stage stage expected to be cancelled
    private static void assertCancelled(
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage) {
        try {
            stage.toCompletableFuture().join();
            fail("Expected catalog stage cancellation");
        } catch (CancellationException expected) {
            // A directly cancelled CompletableFuture reports cancellation without a wrapper.
        } catch (CompletionException wrapped) {
            assertInstanceOf(CancellationException.class, wrapped.getCause());
        }
    }

    /// Download provider returning one exact fetched game list and recording every request.
    @NotNullByDefault
    private static final class RecordingDownloadProvider extends DownloadProvider {
        /// Rows returned by every game catalog fetch.
        private final @Unmodifiable List<ComponentRemoteVersion> rows;

        /// Requested component types in invocation order.
        private final CopyOnWriteArrayList<String> requestedTypes = new CopyOnWriteArrayList<>();

        /// Creates a provider for one fixed row snapshot.
        ///
        /// @param rows fetched rows
        RecordingDownloadProvider(List<? extends ComponentRemoteVersion> rows) {
            this.rows = List.copyOf(rows);
        }

        /// Records the request and exposes the fixed row snapshot through a stopped task.
        ///
        /// @param type        requested component type
        /// @param gameVersion requested game version, or null for the game catalog
        /// @param refresh     whether a refresh was requested
        /// @return stopped task owned by the source executor factory
        @Override
        public @Unmodifiable Task<ComponentRemoteVersionList<?>> getVersionsAsync(
                GameComponentType type, @Nullable GameVersionNumber gameVersion, boolean refresh) {
            requestedTypes.add(type.getPatchId());
            return new RowsTask(type, rows);
        }

        /// Returns an immutable snapshot of requested component identifiers.
        ///
        /// @return requested identifiers
        private @Unmodifiable List<String> requestedTypes() {
            return List.copyOf(requestedTypes);
        }
    }

    /// Stopped task that exposes one fixed row snapshot to the catalog source.
    @NotNullByDefault
    private static final class RowsTask extends Task<ComponentRemoteVersionList<?>> {
        /// Creates one stopped task for a fixed row snapshot.
        ///
        /// @param type requested component type
        /// @param rows fetched rows
        RowsTask(GameComponentType type, List<ComponentRemoteVersion> rows) {
            setResult(ComponentRemoteVersionList.of(type, new TreeSet<>(rows)));
        }

        /// Performs no work because the test executor owns the terminal event.
        @Override
        public void execute() {
            // The controlled executor never runs the task.
        }
    }

    /// Factory recording controlled executors and optionally failing the next start invocation.
    @NotNullByDefault
    private static final class ControlledExecutorFactory
            implements DownloadProviderGameVersionCatalogSource.TaskExecutorFactory {
        /// Executors created in request-start order.
        private final List<ControlledTaskExecutor> executors = new ArrayList<>();

        /// Failure thrown by the next created executor start, or null for a normal start.
        private @Nullable RuntimeException nextStartFailure;

        /// Creates and records one controlled executor.
        ///
        /// @param task source catalog task
        /// @return controlled stopped executor
        @Override
        public synchronized TaskExecutor create(Task<?> task) {
            ControlledTaskExecutor executor = new ControlledTaskExecutor(task, nextStartFailure);
            nextStartFailure = null;
            executors.add(executor);
            return executor;
        }

        /// Configures the next executor start to throw one exact runtime failure.
        ///
        /// @param failure failure to throw
        private synchronized void failNextStart(RuntimeException failure) {
            if (nextStartFailure != null) {
                throw new IllegalStateException("A start failure is already pending");
            }
            nextStartFailure = failure;
        }

        /// Returns one created executor.
        ///
        /// @param index creation index
        /// @return controlled executor
        private synchronized ControlledTaskExecutor executor(int index) {
            return executors.get(index);
        }

        /// Returns the exact number of created executors.
        ///
        /// @return executor count
        private synchronized int executorCount() {
            return executors.size();
        }
    }

    /// Deterministic executor that exposes terminal events without running its task.
    @NotNullByDefault
    private static final class ControlledTaskExecutor extends TaskExecutor {
        /// Optional exact runtime failure thrown after start becomes cancellable.
        private final @Nullable RuntimeException startFailure;

        /// Number of legal cancellation requests received after start returned.
        private int cancelCount;

        /// Whether start has returned and cancellation is therefore legal.
        private boolean startReturned;

        /// Whether a terminal event has already been emitted.
        private boolean terminal;

        /// Creates one stopped controlled executor.
        ///
        /// @param task         inert catalog task
        /// @param startFailure optional synchronous start failure
        ControlledTaskExecutor(Task<?> task, @Nullable RuntimeException startFailure) {
            super(task);
            this.startFailure = startFailure;
        }

        /// Records start and optionally throws the configured failure.
        ///
        /// @return this executor
        @Override
        public TaskExecutor start() {
            startReturned = true;
            if (startFailure != null) {
                throw startFailure;
            }
            return this;
        }

        /// Rejects the unused synchronous execution path.
        ///
        /// @return never returns
        @Override
        public boolean test() {
            throw new UnsupportedOperationException("Controlled executor has no synchronous path");
        }

        /// Records cancellation only after start has returned.
        @Override
        public synchronized void cancel() {
            if (!startReturned) {
                throw new IllegalStateException("Cancellation was forwarded before start returned");
            }
            if (terminal) {
                throw new AssertionError("Cancellation was forwarded after terminal onStop");
            }
            cancelled = true;
            cancelCount++;
        }

        /// Emits one terminal event with an optional exact failure object.
        ///
        /// @param success         terminal success flag
        /// @param terminalFailure terminal failure, or null
        private synchronized void finish(boolean success, @Nullable Throwable terminalFailure) {
            if (!startReturned) {
                throw new IllegalStateException("Cannot finish before start returns");
            }
            if (terminal) {
                throw new IllegalStateException("Controlled executor already stopped");
            }
            terminal = true;
            failure = terminalFailure;
            exception = terminalFailure instanceof Exception exceptionValue ? exceptionValue : null;
            notifyTaskListeners(listener -> listener.onStop(success, this));
        }

        /// Returns the number of successful cancellation requests.
        ///
        /// @return cancellation count
        private synchronized int cancelCount() {
            return cancelCount;
        }
    }
}
