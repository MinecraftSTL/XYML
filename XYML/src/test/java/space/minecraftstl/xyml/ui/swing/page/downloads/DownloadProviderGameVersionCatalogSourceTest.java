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
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.DownloadProviderWrapper;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.download.VersionList;
import space.minecraftstl.xyml.download.game.GameRemoteVersion;
import space.minecraftstl.xyml.game.ReleaseType;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Verifies provider snapshotting, serialized task ownership, mapping, and cancellation semantics.
@NotNullByDefault
final class DownloadProviderGameVersionCatalogSourceTest {
    /// A wrapper switch cannot mix the first request's refresh and read, while the next request sees the new provider.
    @Test
    @Timeout(10)
    void unwrapsProviderAndMapsTheExactRefreshedList() throws Exception {
        Instant unobfuscatedDate = Instant.parse("2025-11-01T00:00:00Z");
        Instant snapshotDate = Instant.parse("2025-05-22T00:00:00Z");
        Instant releaseDate = Instant.parse("2024-08-08T00:00:00Z");
        Instant pendingDate = Instant.parse("2024-05-01T00:00:00Z");
        Instant aprilFoolsDate = Instant.parse("2024-04-01T00:00:00Z");
        RecordingVersionList firstList = new RecordingVersionList(List.of(
                gameVersion("1.21.1", ReleaseType.RELEASE, releaseDate),
                gameVersion("25w21a", ReleaseType.SNAPSHOT, snapshotDate),
                gameVersion("24w14potato", "april-special", ReleaseType.SNAPSHOT, aprilFoolsDate),
                gameVersion("1.21-pre1", ReleaseType.PENDING, pendingDate),
                gameVersion("25w45a_unobfuscated", ReleaseType.UNOBFUSCATED, unobfuscatedDate),
                gameVersion("unknown-build", ReleaseType.UNKNOWN, Instant.parse("2023-01-01T00:00:00Z")),
                gameVersion("b1.7.3", ReleaseType.OLD_BETA, null),
                gameVersion("a1.2.6", ReleaseType.OLD_ALPHA, null),
                gameVersion("1.21.1", ReleaseType.SNAPSHOT, Instant.parse("2020-01-01T00:00:00Z"))));
        RecordingVersionList secondList = new RecordingVersionList(List.of(
                gameVersion("1.22", ReleaseType.RELEASE, Instant.parse("2026-01-01T00:00:00Z"))));
        RecordingDownloadProvider firstProvider = new RecordingDownloadProvider(firstList);
        RecordingDownloadProvider secondProvider = new RecordingDownloadProvider(secondList);
        DownloadProviderWrapper wrapper = new DownloadProviderWrapper(firstProvider);
        ControlledExecutorFactory executors = new ControlledExecutorFactory();

        try (DownloadProviderGameVersionCatalogSource source =
                     new DownloadProviderGameVersionCatalogSource(wrapper, executors)) {
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> firstStage =
                    source.load(new LoadCancellation());
            wrapper.setProvider(secondProvider);
            executors.executor(0).finish(true, null);

            @Unmodifiable List<GameVersionCatalogItem> firstItems = await(firstStage);
            assertEquals(
                    List.of(
                            "25w45a_unobfuscated",
                            "25w21a",
                            "1.21.1",
                            "1.21-pre1",
                            "april-special",
                            "unknown-build",
                            "b1.7.3",
                            "a1.2.6"),
                    firstItems.stream().map(GameVersionCatalogItem::versionId).toList());
            assertEquals(
                    List.of(
                            GameVersionKind.SNAPSHOT,
                            GameVersionKind.SNAPSHOT,
                            GameVersionKind.RELEASE,
                            GameVersionKind.SNAPSHOT,
                            GameVersionKind.APRIL_FOOLS,
                            GameVersionKind.OLD,
                            GameVersionKind.OLD,
                            GameVersionKind.OLD),
                    firstItems.stream().map(GameVersionCatalogItem::kind).toList());
            assertEquals(releaseDate, firstItems.get(2).releaseDate().orElseThrow());
            assertTrue(firstItems.get(6).releaseDate().isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> firstItems.add(firstItems.get(0)));

            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> secondStage =
                    source.load(new LoadCancellation());
            executors.executor(1).finish(true, null);
            assertEquals("1.22", await(secondStage).get(0).versionId());
        }

        assertEquals(List.of("game"), firstProvider.requestedIds());
        assertEquals(List.of("game"), secondProvider.requestedIds());
        assertEquals(List.of("game"), firstList.refreshScopes());
        assertEquals(List.of("game"), firstList.readScopes());
        assertEquals(List.of("game"), secondList.refreshScopes());
        assertEquals(List.of("game"), secondList.readScopes());
        assertTrue(executors.executor(0).listenerPresentAtStart());
        assertTrue(executors.executor(1).listenerPresentAtStart());
    }

    /// Consecutive requests cancel the running stage, replace the waiter, and never overlap list refresh tasks.
    @Test
    @Timeout(10)
    void serializesRunningAndLatestPendingLoads() throws Exception {
        RecordingVersionList versionList = new RecordingVersionList(List.of(
                gameVersion("latest", ReleaseType.RELEASE, Instant.parse("2026-02-01T00:00:00Z"))));
        RecordingDownloadProvider provider = new RecordingDownloadProvider(versionList);
        ControlledExecutorFactory executors = new ControlledExecutorFactory();

        try (DownloadProviderGameVersionCatalogSource source =
                     new DownloadProviderGameVersionCatalogSource(provider, executors)) {
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> first =
                    source.load(new LoadCancellation());
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> replacedPending =
                    source.load(new LoadCancellation());
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> latest =
                    source.load(new LoadCancellation());

            assertCancelled(first);
            assertCancelled(replacedPending);
            assertEquals(1, executors.executorCount());
            assertEquals(1, executors.executor(0).cancelCalls());
            assertFalse(latest.toCompletableFuture().isDone());

            executors.executor(0).finish(false, null);
            assertEquals(2, executors.executorCount());
            assertEquals(List.of("game", "game"), versionList.refreshScopes());

            executors.executor(1).finish(true, null);
            assertEquals("latest", await(latest).get(0).versionId());
        }
    }

    /// A pre-cancelled signal performs no provider or task work, and closure remains the dominant terminal state.
    @Test
    void rejectsPreCancelledAndPostCloseLoads() {
        RecordingVersionList versionList = new RecordingVersionList(List.of());
        RecordingDownloadProvider provider = new RecordingDownloadProvider(versionList);
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        DownloadProviderGameVersionCatalogSource source =
                new DownloadProviderGameVersionCatalogSource(provider, executors);
        LoadCancellation cancellation = new LoadCancellation();
        cancellation.cancel();

        assertCancelled(source.load(cancellation));
        assertTrue(provider.requestedIds().isEmpty());
        assertEquals(0, executors.executorCount());

        source.close();
        source.close();
        assertThrows(IllegalStateException.class, () -> source.load(new LoadCancellation()));
        assertThrows(IllegalStateException.class, () -> source.load(cancellation));
    }

    /// Cancellation observed at terminal mapping discards a successful core result without inventing polling.
    @Test
    void cancellationDuringRefreshDiscardsTheResult() {
        RecordingVersionList versionList = new RecordingVersionList(List.of(
                gameVersion("cancelled", ReleaseType.RELEASE, Instant.parse("2026-03-01T00:00:00Z"))));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        LoadCancellation cancellation = new LoadCancellation();

        try (DownloadProviderGameVersionCatalogSource source =
                     new DownloadProviderGameVersionCatalogSource(
                             new RecordingDownloadProvider(versionList),
                             executors)) {
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage = source.load(cancellation);
            cancellation.cancel();
            executors.executor(0).finish(true, null);

            assertCancelled(stage);
            assertEquals(0, executors.executor(0).cancelCalls());
            assertTrue(versionList.readScopes().isEmpty());
        }
    }

    /// Closing while start is on the stack cancels the stage immediately and defers executor cancellation safely.
    @Test
    @Timeout(10)
    void closeDefersCancellationUntilStartReturns() throws Exception {
        RecordingVersionList versionList = new RecordingVersionList(List.of(
                gameVersion("ignored", ReleaseType.RELEASE, Instant.parse("2026-04-01T00:00:00Z"))));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        StartGate gate = executors.blockNextStart();
        DownloadProviderGameVersionCatalogSource source =
                new DownloadProviderGameVersionCatalogSource(
                        new RecordingDownloadProvider(versionList),
                        executors);
        ExecutorService lifecycleCallers = Executors.newFixedThreadPool(2);
        try {
            Future<CompletionStage<@Unmodifiable List<GameVersionCatalogItem>>> loadCall =
                    lifecycleCallers.submit(() -> source.load(new LoadCancellation()));
            assertTrue(gate.awaitEntered());

            ControlledTaskExecutor executor = executors.executor(0);
            assertTrue(executor.listenerPresentAtStart());
            AtomicReference<@Nullable Thread> closeThread = new AtomicReference<>();
            Future<?> closeCall = lifecycleCallers.submit(() -> {
                closeThread.set(Thread.currentThread());
                source.close();
            });
            awaitBlockedThread(closeThread);
            assertFalse(closeCall.isDone());
            assertEquals(0, executor.cancelCalls());

            gate.release();
            closeCall.get(5, TimeUnit.SECONDS);
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage =
                    loadCall.get(5, TimeUnit.SECONDS);
            assertCancelled(stage);
            assertEquals(1, executor.cancelCalls());
        } finally {
            gate.release();
            source.close();
            lifecycleCallers.shutdownNow();
        }
    }

    /// A close re-entering from start cannot self-deadlock and still forwards cancellation after start returns.
    @Test
    @Timeout(10)
    void reentrantCloseDuringStartCancelsAfterStartReturns() {
        RecordingVersionList versionList = new RecordingVersionList(List.of(
                gameVersion("reentrant-close", ReleaseType.RELEASE, Instant.parse("2026-04-02T00:00:00Z"))));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        AtomicReference<@Nullable DownloadProviderGameVersionCatalogSource> sourceReference =
                new AtomicReference<>();
        executors.runOnNextStart(() -> sourceReference.get().close());
        DownloadProviderGameVersionCatalogSource source =
                new DownloadProviderGameVersionCatalogSource(
                        new RecordingDownloadProvider(versionList),
                        executors);
        sourceReference.set(source);

        CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage =
                source.load(new LoadCancellation());

        assertCancelled(stage);
        assertEquals(1, executors.executor(0).cancelAttempts());
        assertEquals(1, executors.executor(0).cancelCalls());
        assertThrows(IllegalStateException.class, () -> source.load(new LoadCancellation()));
        source.close();
    }

    /// Close waits for a provider snapshot, after which the in-flight load observes closure and creates no task.
    @Test
    @Timeout(10)
    void closeWaitsForProviderSnapshotInvocation() throws Exception {
        RecordingVersionList versionList = new RecordingVersionList(List.of());
        ProviderGate providerGate = new ProviderGate();
        RecordingDownloadProvider provider = new RecordingDownloadProvider(versionList, providerGate);
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        DownloadProviderGameVersionCatalogSource source =
                new DownloadProviderGameVersionCatalogSource(provider, executors);
        ExecutorService lifecycleCallers = Executors.newFixedThreadPool(2);
        try {
            Future<CompletionStage<@Unmodifiable List<GameVersionCatalogItem>>> loadCall =
                    lifecycleCallers.submit(() -> source.load(new LoadCancellation()));
            assertTrue(providerGate.awaitEntered());

            AtomicReference<@Nullable Thread> closeThread = new AtomicReference<>();
            Future<?> closeCall = lifecycleCallers.submit(() -> {
                closeThread.set(Thread.currentThread());
                source.close();
            });
            awaitBlockedThread(closeThread);
            assertFalse(closeCall.isDone());

            providerGate.release();
            ExecutionException loadFailure = assertThrows(
                    ExecutionException.class,
                    () -> loadCall.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, loadFailure.getCause());
            closeCall.get(5, TimeUnit.SECONDS);
            assertEquals(0, executors.executorCount());
        } finally {
            providerGate.release();
            source.close();
            lifecycleCallers.shutdownNow();
        }
    }

    /// Every concurrent close crosses the same cleanup barrier instead of returning during an earlier cancellation.
    @Test
    @Timeout(10)
    void concurrentCloseWaitsForDetachedResourceCleanup() throws Exception {
        RecordingVersionList versionList = new RecordingVersionList(List.of(
                gameVersion("close-barrier", ReleaseType.RELEASE, Instant.parse("2026-04-03T00:00:00Z"))));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        CancelGate cancelGate = executors.blockNextCancellation();
        DownloadProviderGameVersionCatalogSource source =
                new DownloadProviderGameVersionCatalogSource(
                        new RecordingDownloadProvider(versionList),
                        executors);
        CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage =
                source.load(new LoadCancellation());
        ExecutorService closeCallers = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstClose = closeCallers.submit(source::close);
            assertTrue(cancelGate.awaitEntered());

            AtomicReference<@Nullable Thread> secondCloseThread = new AtomicReference<>();
            Future<?> secondClose = closeCallers.submit(() -> {
                secondCloseThread.set(Thread.currentThread());
                source.close();
            });
            awaitBlockedThread(secondCloseThread);
            assertFalse(secondClose.isDone());

            cancelGate.release();
            firstClose.get(5, TimeUnit.SECONDS);
            secondClose.get(5, TimeUnit.SECONDS);
            assertCancelled(stage);
            assertEquals(1, executors.executor(0).cancelCalls());
        } finally {
            cancelGate.release();
            closeCallers.shutdownNow();
            source.close();
        }
    }

    /// A close cleanup Error remains the exact failure observed by every later close caller.
    @Test
    void closeReplaysItsFirstCleanupFailure() {
        RecordingVersionList versionList = new RecordingVersionList(List.of(
                gameVersion("close-error", ReleaseType.RELEASE, Instant.parse("2026-04-04T00:00:00Z"))));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        AssertionError closeError = new AssertionError("executor cancellation failed during close");
        executors.failNextCancellation(closeError);
        DownloadProviderGameVersionCatalogSource source =
                new DownloadProviderGameVersionCatalogSource(
                        new RecordingDownloadProvider(versionList),
                        executors);
        CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage =
                source.load(new LoadCancellation());

        assertSame(closeError, assertThrows(AssertionError.class, source::close));
        assertCancelled(stage);
        assertSame(closeError, assertThrows(AssertionError.class, source::close));
        assertEquals(1, executors.executor(0).cancelAttempts());
    }

    /// A cancellation-control failure terminates the new request instead of leaving an unreachable pending stage.
    @Test
    @Timeout(10)
    void cancellationFailureFailsPendingAndAllowsLaterRetry() throws Exception {
        RecordingVersionList versionList = new RecordingVersionList(List.of(
                gameVersion("after-cancel-retry", ReleaseType.RELEASE, Instant.parse("2026-04-05T00:00:00Z"))));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        IllegalStateException cancellationFailure = new IllegalStateException("executor cancellation failed");

        try (DownloadProviderGameVersionCatalogSource source =
                     new DownloadProviderGameVersionCatalogSource(
                             new RecordingDownloadProvider(versionList),
                             executors)) {
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> first =
                    source.load(new LoadCancellation());
            executors.executor(0).failNextCancellation(cancellationFailure);
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> failedPending =
                    source.load(new LoadCancellation());

            assertCancelled(first);
            assertFailureIdentity(failedPending, cancellationFailure);
            assertEquals(1, executors.executor(0).cancelAttempts());
            assertEquals(0, executors.executor(0).cancelCalls());

            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> retry =
                    source.load(new LoadCancellation());
            assertEquals(2, executors.executor(0).cancelAttempts());
            assertEquals(1, executors.executor(0).cancelCalls());
            executors.executor(0).finish(false, null);
            executors.executor(1).finish(true, null);
            assertEquals("after-cancel-retry", await(retry).get(0).versionId());
        }
    }

    /// A load waiting behind start receives cancellation failure without failing the starter invocation.
    @Test
    @Timeout(10)
    void blockedLoadCancellationFailureTerminatesPendingStage() throws Exception {
        RecordingVersionList versionList = new RecordingVersionList(List.of(
                gameVersion("after-deferred-cancel", ReleaseType.RELEASE, Instant.parse("2026-04-06T00:00:00Z"))));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        StartGate startGate = executors.blockNextStart();
        IllegalStateException cancellationFailure = new IllegalStateException("deferred cancellation failed");
        DownloadProviderGameVersionCatalogSource source =
                new DownloadProviderGameVersionCatalogSource(
                        new RecordingDownloadProvider(versionList),
                        executors);
        ExecutorService loadCallers = Executors.newFixedThreadPool(2);
        try {
            Future<CompletionStage<@Unmodifiable List<GameVersionCatalogItem>>> firstLoad =
                    loadCallers.submit(() -> source.load(new LoadCancellation()));
            assertTrue(startGate.awaitEntered());
            executors.executor(0).failNextCancellation(cancellationFailure);

            AtomicReference<@Nullable Thread> pendingThread = new AtomicReference<>();
            Future<CompletionStage<@Unmodifiable List<GameVersionCatalogItem>>> pendingLoad =
                    loadCallers.submit(() -> {
                        pendingThread.set(Thread.currentThread());
                        return source.load(new LoadCancellation());
                    });
            awaitBlockedThread(pendingThread);

            startGate.release();
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> firstStage =
                    firstLoad.get(5, TimeUnit.SECONDS);
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> pendingStage =
                    pendingLoad.get(5, TimeUnit.SECONDS);
            assertCancelled(firstStage);
            assertFailureIdentity(pendingStage, cancellationFailure);
            assertEquals(1, executors.executor(0).cancelAttempts());
            assertEquals(0, executors.executor(0).cancelCalls());

            executors.executor(0).finish(false, null);
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> retry =
                    source.load(new LoadCancellation());
            executors.executor(1).finish(true, null);
            assertEquals("after-deferred-cancel", await(retry).get(0).versionId());
        } finally {
            startGate.release();
            source.close();
            loadCallers.shutdownNow();
        }
    }

    /// A core task Error is retained as the exact exceptional-stage cause and releases the active slot for retry.
    @Test
    @Timeout(10)
    void preservesTaskErrorIdentityAndAllowsRetry() throws Exception {
        RecordingVersionList versionList = new RecordingVersionList(List.of(
                gameVersion("retry", ReleaseType.RELEASE, Instant.parse("2026-05-01T00:00:00Z"))));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        AssertionError taskError = new AssertionError("catalog task error");

        try (DownloadProviderGameVersionCatalogSource source =
                     new DownloadProviderGameVersionCatalogSource(
                             new RecordingDownloadProvider(versionList),
                             executors)) {
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> failedStage =
                    source.load(new LoadCancellation());
            executors.executor(0).finish(false, taskError);
            assertFailureIdentity(failedStage, taskError);

            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> retryStage =
                    source.load(new LoadCancellation());
            executors.executor(1).finish(true, null);
            assertEquals("retry", await(retryStage).get(0).versionId());
        }
    }

    /// A synchronous executor-start rejection fails its stage, cancels partial ownership, and leaves the slot reusable.
    @Test
    @Timeout(10)
    void cleansOperationAfterExecutorStartFailure() throws Exception {
        RecordingVersionList versionList = new RecordingVersionList(List.of(
                gameVersion("after-rejection", ReleaseType.RELEASE, Instant.parse("2026-05-02T00:00:00Z"))));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        RejectedExecutionException rejection = new RejectedExecutionException("catalog executor rejected start");
        executors.failNextStart(rejection);

        try (DownloadProviderGameVersionCatalogSource source =
                     new DownloadProviderGameVersionCatalogSource(
                             new RecordingDownloadProvider(versionList),
                             executors)) {
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> rejectedStage =
                    source.load(new LoadCancellation());
            assertFailureIdentity(rejectedStage, rejection);
            assertEquals(1, executors.executor(0).cancelCalls());

            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> retryStage =
                    source.load(new LoadCancellation());
            executors.executor(1).finish(true, null);
            assertEquals("after-rejection", await(retryStage).get(0).versionId());
        }
    }

    /// A cancellation Error outranks a RuntimeException from start and retains that start failure as suppressed.
    @Test
    void cancellationErrorOutranksExecutorStartRuntimeFailure() {
        RecordingVersionList versionList = new RecordingVersionList(List.of());
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        RejectedExecutionException startFailure = new RejectedExecutionException("start rejected");
        AssertionError cancellationError = new AssertionError("cancel after rejected start failed");
        executors.failNextStart(startFailure);
        executors.failNextCancellation(cancellationError);

        DownloadProviderGameVersionCatalogSource source =
                new DownloadProviderGameVersionCatalogSource(
                        new RecordingDownloadProvider(versionList),
                        executors);
        try {
            AssertionError thrown = assertThrows(
                    AssertionError.class,
                    () -> source.load(new LoadCancellation()));
            assertSame(cancellationError, thrown);
            assertEquals(1, thrown.getSuppressed().length);
            assertSame(startFailure, thrown.getSuppressed()[0]);
        } finally {
            source.close();
        }
    }

    /// A synchronous terminal mapping Error escaping start never triggers cancellation of the stopped executor.
    @Test
    void synchronousStopErrorDoesNotCancelTerminalExecutor() {
        RecordingVersionList versionList = new RecordingVersionList(List.of());
        AssertionError mappingError = new AssertionError("mapping failed during synchronous stop");
        versionList.failReadsWith(mappingError);
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        executors.stopNextSynchronously();

        DownloadProviderGameVersionCatalogSource source =
                new DownloadProviderGameVersionCatalogSource(
                        new RecordingDownloadProvider(versionList),
                        executors);
        try {
            assertSame(mappingError, assertThrows(
                    AssertionError.class,
                    () -> source.load(new LoadCancellation())));
            assertEquals(0, executors.executor(0).cancelAttempts());
        } finally {
            source.close();
        }
    }

    /// Provider lookup and terminal mapping callbacks cannot invert two lifecycle barriers while closing.
    @Test
    @Timeout(10)
    void providerAndTerminalCallbacksCannotDeadlockDuringClose() throws Exception {
        RecordingVersionList versionList = new RecordingVersionList(List.of(
                gameVersion("barrier", ReleaseType.RELEASE, Instant.parse("2026-05-03T00:00:00Z"))));
        RecordingDownloadProvider provider = new RecordingDownloadProvider(versionList);
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        DownloadProviderGameVersionCatalogSource source =
                new DownloadProviderGameVersionCatalogSource(provider, executors);
        CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> activeStage =
                source.load(new LoadCancellation());

        AtomicReference<@Nullable Thread> providerThreadReference = new AtomicReference<>();
        AtomicReference<@Nullable Throwable> providerFailure = new AtomicReference<>();
        AtomicReference<@Nullable Throwable> terminalFailure = new AtomicReference<>();
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch providerReturned = new CountDownLatch(1);
        CountDownLatch terminalReturned = new CountDownLatch(1);
        provider.runOnNextRequest(source::close);
        versionList.runOnNextRead(() -> {
            readEntered.countDown();
            awaitBlockedThread(providerThreadReference);
            source.close();
        });

        Thread terminalThread = daemonThread(
                "game-version-terminal-close",
                () -> executors.executor(0).finish(true, null),
                terminalFailure,
                terminalReturned);
        Thread providerThread = daemonThread(
                "game-version-provider-close",
                () -> source.load(new LoadCancellation()),
                providerFailure,
                providerReturned);
        providerThreadReference.set(providerThread);

        terminalThread.start();
        assertTrue(readEntered.await(5L, TimeUnit.SECONDS));
        providerThread.start();

        assertTrue(terminalReturned.await(5L, TimeUnit.SECONDS));
        assertTrue(providerReturned.await(5L, TimeUnit.SECONDS));
        assertNull(terminalFailure.get());
        assertInstanceOf(IllegalStateException.class, providerFailure.get());
        assertCancelled(activeStage);
        source.close();
    }

    /// A supersede marker wins the terminal-state race even when the old task reports a real failure.
    @Test
    void supersededFailureStillCancelsOldStage() throws Exception {
        RecordingVersionList versionList = new RecordingVersionList(List.of(
                gameVersion("latest-after-failure", ReleaseType.RELEASE,
                        Instant.parse("2026-05-04T00:00:00Z"))));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        AssertionError supersededFailure = new AssertionError("superseded task failed");

        try (DownloadProviderGameVersionCatalogSource source =
                     new DownloadProviderGameVersionCatalogSource(
                             new RecordingDownloadProvider(versionList),
                             executors)) {
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> oldStage =
                    source.load(new LoadCancellation());
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> latestStage =
                    source.load(new LoadCancellation());

            executors.executor(0).finish(false, supersededFailure);
            assertCancelled(oldStage);
            executors.executor(1).finish(true, null);
            assertEquals("latest-after-failure", await(latestStage).get(0).versionId());
        }
    }

    /// A cooperative cancellation observed before terminal arbitration outranks a recorded task failure.
    @Test
    void cancellationSignalOutranksTerminalFailure() {
        RecordingVersionList versionList = new RecordingVersionList(List.of());
        ControlledExecutorFactory executors = new ControlledExecutorFactory();
        LoadCancellation cancellation = new LoadCancellation();
        AssertionError terminalFailure = new AssertionError("cancelled task failed");

        try (DownloadProviderGameVersionCatalogSource source =
                     new DownloadProviderGameVersionCatalogSource(
                             new RecordingDownloadProvider(versionList),
                             executors)) {
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage =
                    source.load(cancellation);
            cancellation.cancel();
            executors.executor(0).finish(false, terminalFailure);

            assertCancelled(stage);
        }
    }

    /// A provider returning a non-game entry fails explicitly instead of publishing a partially typed catalog.
    @Test
    void rejectsUnexpectedRemoteVersionType() {
        RemoteVersion unsupported = new RemoteVersion(
                "unsupported",
                "1.21.1",
                "unsupported",
                Instant.parse("2026-06-01T00:00:00Z"),
                List.of("https://example.invalid/unsupported"));
        RecordingVersionList versionList = new RecordingVersionList(List.of(unsupported));
        ControlledExecutorFactory executors = new ControlledExecutorFactory();

        try (DownloadProviderGameVersionCatalogSource source =
                     new DownloadProviderGameVersionCatalogSource(
                             new RecordingDownloadProvider(versionList),
                             executors)) {
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage =
                    source.load(new LoadCancellation());
            executors.executor(0).finish(true, null);

            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> stage.toCompletableFuture().join());
            IllegalStateException cause = assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(cause.getMessage().contains(RemoteVersion.class.getName()));
        }
    }

    /// Creates one core game-version row with explicit nullable release date handling.
    ///
    /// @param versionId game and self version ID
    /// @param type upstream release type
    /// @param releaseDate upstream date, or null
    /// @return core remote game version
    private static GameRemoteVersion gameVersion(
            String versionId,
            ReleaseType type,
            @Nullable Instant releaseDate) {
        return gameVersion(versionId, versionId, type, releaseDate);
    }

    /// Creates one core game-version row whose display ID differs from its classification version.
    ///
    /// @param gameVersion Minecraft version used for classification
    /// @param selfVersion stable catalog ID
    /// @param type upstream release type
    /// @param releaseDate upstream date, or null
    /// @return core remote game version
    private static GameRemoteVersion gameVersion(
            String gameVersion,
            String selfVersion,
            ReleaseType type,
            @Nullable Instant releaseDate) {
        return new GameRemoteVersion(
                gameVersion,
                selfVersion,
                List.of("https://example.invalid/" + selfVersion),
                type,
                releaseDate);
    }

    /// Waits for one successful stage with a bounded timeout.
    ///
    /// @param stage stage to await
    /// @return completed immutable catalog
    private static @Unmodifiable List<GameVersionCatalogItem> await(
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /// Asserts direct or wrapped CompletableFuture cancellation without depending on JDK copy implementation details.
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

    /// Asserts that an exceptional stage retains one exact failure object.
    ///
    /// @param stage failed stage
    /// @param expectedFailure expected original failure
    private static void assertFailureIdentity(
            CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage,
            Throwable expectedFailure) {
        CompletionException completionFailure = assertThrows(
                CompletionException.class,
                () -> stage.toCompletableFuture().join());
        assertSame(expectedFailure, completionFailure.getCause());
    }

    /// Waits until one captured thread is blocked while entering a source lifecycle monitor.
    ///
    /// @param threadReference reference populated immediately before the lifecycle call
    private static void awaitBlockedThread(AtomicReference<@Nullable Thread> threadReference) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            @Nullable Thread thread = threadReference.get();
            if (thread != null) {
                if (thread.getState() == Thread.State.BLOCKED) {
                    return;
                }
                if (thread.getState() == Thread.State.TERMINATED) {
                    fail("Lifecycle thread terminated before reaching its barrier");
                }
            }
            Thread.yield();
        }
        fail("Timed out waiting for lifecycle thread to block");
    }

    /// Creates a daemon test thread that captures every terminal failure and signals completion.
    ///
    /// @param name diagnostic thread name
    /// @param action thread action
    /// @param failure captured failure destination
    /// @param completion terminal completion signal
    /// @return unstarted daemon thread
    private static Thread daemonThread(
            String name,
            Runnable action,
            AtomicReference<@Nullable Throwable> failure,
            CountDownLatch completion) {
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable actionFailure) {
                failure.set(actionFailure);
            } finally {
                completion.countDown();
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    /// Throws one configured unchecked test failure without replacing its identity.
    ///
    /// @param failure runtime exception or Error to throw
    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("Controlled failure must be unchecked", failure);
    }

    /// Download provider recording every requested list ID and returning one exact list instance.
    @NotNullByDefault
    private static final class RecordingDownloadProvider implements DownloadProvider {
        /// Exact list returned for the game list ID.
        private final VersionList<?> versionList;

        /// Optional gate blocking the provider snapshot invocation.
        private @Nullable ProviderGate requestGate;

        /// Callback invoked by the next provider lookup, or null for no callback.
        private @Nullable Runnable requestCallback;

        /// Requested list IDs in invocation order.
        private final CopyOnWriteArrayList<String> requestedIds = new CopyOnWriteArrayList<>();

        /// Creates a provider for one exact list.
        ///
        /// @param versionList list to return
        private RecordingDownloadProvider(VersionList<?> versionList) {
            this(versionList, null);
        }

        /// Creates a provider whose list lookup may wait on a deterministic gate.
        ///
        /// @param versionList list to return
        /// @param requestGate optional provider invocation gate
        private RecordingDownloadProvider(
                VersionList<?> versionList,
                @Nullable ProviderGate requestGate) {
            this.versionList = versionList;
            this.requestGate = requestGate;
        }

        /// Returns no network manifest locations because tests never execute the core task.
        ///
        /// @return empty URI list
        @Override
        public @Unmodifiable List<URI> getVersionListURLs() {
            return List.of();
        }

        /// Returns no asset candidates because catalog tests do not download assets.
        ///
        /// @param assetObjectLocation ignored asset location
        /// @return empty URI list
        @Override
        public @Unmodifiable List<URI> getAssetObjectCandidates(String assetObjectLocation) {
            return List.of();
        }

        /// Leaves one URL unchanged for the unused injection boundary.
        ///
        /// @param baseURL input URL
        /// @return unchanged URL
        @Override
        public String injectURL(String baseURL) {
            return baseURL;
        }

        /// Records and returns the exact configured list.
        ///
        /// @param id requested list ID
        /// @return exact configured list
        @Override
        public VersionList<?> getVersionListById(String id) {
            @Nullable ProviderGate gate;
            @Nullable Runnable callback;
            synchronized (this) {
                gate = requestGate;
                requestGate = null;
                callback = requestCallback;
                requestCallback = null;
            }
            if (gate != null) {
                gate.enter();
            }
            if (callback != null) {
                callback.run();
            }
            requestedIds.add(id);
            return versionList;
        }

        /// Blocks the next provider lookup on a deterministic gate.
        ///
        /// @return gate assigned to the next lookup
        private synchronized ProviderGate blockNextRequest() {
            if (requestGate != null) {
                throw new IllegalStateException("A provider request gate is already pending");
            }
            requestGate = new ProviderGate();
            return requestGate;
        }

        /// Runs one callback from the next provider lookup.
        ///
        /// @param callback callback to invoke
        private synchronized void runOnNextRequest(Runnable callback) {
            if (requestCallback != null) {
                throw new IllegalStateException("A provider callback is already pending");
            }
            requestCallback = callback;
        }

        /// Returns one unused test download slot.
        ///
        /// @return one
        @Override
        public int getConcurrency() {
            return 1;
        }

        /// Returns an immutable snapshot of requested list IDs.
        ///
        /// @return requested IDs
        private @Unmodifiable List<String> requestedIds() {
            return List.copyOf(requestedIds);
        }
    }

    /// Version list recording the refresh and read scope while exposing test-selected rows.
    @NotNullByDefault
    private static final class RecordingVersionList extends VersionList<RemoteVersion> {
        /// Immutable rows returned by the final list snapshot method.
        private final @Unmodifiable List<RemoteVersion> entries;

        /// Parameterized refresh scopes in invocation order.
        private final CopyOnWriteArrayList<String> refreshScopes = new CopyOnWriteArrayList<>();

        /// Snapshot-read scopes in invocation order.
        private final CopyOnWriteArrayList<String> readScopes = new CopyOnWriteArrayList<>();

        /// Exact unchecked failure thrown by the next snapshot read, or null for normal rows.
        private @Nullable Throwable readFailure;

        /// Callback invoked by the next snapshot read, or null for no callback.
        private @Nullable Runnable readCallback;

        /// Creates a list with immutable test rows.
        ///
        /// @param entries rows returned after controlled task success
        private RecordingVersionList(List<? extends RemoteVersion> entries) {
            this.entries = List.copyOf(entries);
        }

        /// Reports typed rows like the production game list.
        ///
        /// @return true
        @Override
        public boolean hasType() {
            return true;
        }

        /// Rejects accidental use of the unsupported no-argument refresh path.
        ///
        /// @return never returns
        @Override
        public Task<?> refreshAsync() {
            throw new AssertionError("The game catalog must use refreshAsync(\"game\")");
        }

        /// Records a parameterized refresh and returns a task owned by the controlled executor.
        ///
        /// @param gameVersion requested scope
        /// @return inert core task
        @Override
        public Task<?> refreshAsync(String gameVersion) {
            refreshScopes.add(gameVersion);
            return new InertTask();
        }

        /// Records the scope read by the final [VersionList#getVersions(String)] snapshot.
        ///
        /// @param gameVersion requested scope
        /// @return immutable test rows
        @Override
        protected @Unmodifiable Collection<RemoteVersion> getVersionsImpl(String gameVersion) {
            readScopes.add(gameVersion);
            @Nullable Throwable failure;
            @Nullable Runnable callback;
            synchronized (this) {
                failure = readFailure;
                readFailure = null;
                callback = readCallback;
                readCallback = null;
            }
            if (failure != null) {
                throwUnchecked(failure);
            }
            if (callback != null) {
                callback.run();
            }
            return entries;
        }

        /// Configures the next snapshot read to throw one exact unchecked failure.
        ///
        /// @param failure failure to throw
        private synchronized void failReadsWith(Throwable failure) {
            readFailure = failure;
        }

        /// Runs one callback from the next snapshot read.
        ///
        /// @param callback callback to invoke
        private synchronized void runOnNextRead(Runnable callback) {
            if (readCallback != null) {
                throw new IllegalStateException("A read callback is already pending");
            }
            readCallback = callback;
        }

        /// Returns an immutable refresh-scope snapshot.
        ///
        /// @return refresh scopes
        private @Unmodifiable List<String> refreshScopes() {
            return List.copyOf(refreshScopes);
        }

        /// Returns an immutable read-scope snapshot.
        ///
        /// @return read scopes
        private @Unmodifiable List<String> readScopes() {
            return List.copyOf(readScopes);
        }
    }

    /// Inert task whose execution is intentionally replaced by a controlled test executor.
    @NotNullByDefault
    private static final class InertTask extends Task<@Nullable Void> {
        /// Performs no work if unexpectedly executed.
        @Override
        public void execute() {
            setResult(null);
        }
    }

    /// Factory recording controlled executors and optionally blocking the next start invocation.
    @NotNullByDefault
    private static final class ControlledExecutorFactory
            implements DownloadProviderGameVersionCatalogSource.TaskExecutorFactory {
        /// Executors created in request-start order.
        private final List<ControlledTaskExecutor> executors = new ArrayList<>();

        /// Gate assigned to the next created executor, or null for an immediate start.
        private @Nullable StartGate nextStartGate;

        /// Failure thrown by the next created executor's start method, or null for normal start.
        private @Nullable RuntimeException nextStartFailure;

        /// Gate assigned to the next created executor cancellation, or null for immediate cancellation.
        private @Nullable CancelGate nextCancelGate;

        /// Failure thrown by the next created executor's first cancellation, or null for success.
        private @Nullable Throwable nextCancellationFailure;

        /// Callback invoked from the next executor start, or null when no reentrant action is requested.
        private @Nullable Runnable nextStartCallback;

        /// Whether the next executor emits successful onStop synchronously from start.
        private boolean nextSynchronousStop;

        /// Creates and records one controlled executor.
        ///
        /// @param task source refresh task
        /// @return controlled stopped executor
        @Override
        public synchronized TaskExecutor create(Task<?> task) {
            ControlledTaskExecutor executor = new ControlledTaskExecutor(
                    task,
                    nextStartGate,
                    nextStartFailure,
                    nextCancelGate,
                    nextCancellationFailure,
                    nextStartCallback,
                    nextSynchronousStop);
            nextStartGate = null;
            nextStartFailure = null;
            nextCancelGate = null;
            nextCancellationFailure = null;
            nextStartCallback = null;
            nextSynchronousStop = false;
            executors.add(executor);
            return executor;
        }

        /// Configures the next executor start to wait for explicit release.
        ///
        /// @return gate used by the next executor
        private synchronized StartGate blockNextStart() {
            if (nextStartGate != null) {
                throw new IllegalStateException("A start gate is already pending");
            }
            nextStartGate = new StartGate();
            return nextStartGate;
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

        /// Configures the next executor cancellation to wait for explicit release.
        ///
        /// @return gate used by the next executor
        private synchronized CancelGate blockNextCancellation() {
            if (nextCancelGate != null) {
                throw new IllegalStateException("A cancellation gate is already pending");
            }
            nextCancelGate = new CancelGate();
            return nextCancelGate;
        }

        /// Configures the next executor's first cancellation to throw one exact unchecked failure.
        ///
        /// @param failure failure to throw
        private synchronized void failNextCancellation(Throwable failure) {
            if (nextCancellationFailure != null) {
                throw new IllegalStateException("A cancellation failure is already pending");
            }
            nextCancellationFailure = failure;
        }

        /// Configures one callback to run reentrantly from the next executor start.
        ///
        /// @param callback callback to invoke
        private synchronized void runOnNextStart(Runnable callback) {
            if (nextStartCallback != null) {
                throw new IllegalStateException("A start callback is already pending");
            }
            nextStartCallback = callback;
        }

        /// Configures the next executor to emit successful onStop before start returns.
        private synchronized void stopNextSynchronously() {
            nextSynchronousStop = true;
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
        /// Optional gate held while start remains on the caller's stack.
        private final @Nullable StartGate startGate;

        /// Optional exact runtime failure thrown after start becomes cancellable.
        private final @Nullable RuntimeException startFailure;

        /// Optional gate held during cancellation.
        private final @Nullable CancelGate cancelGate;

        /// Exact one-shot unchecked cancellation failure, or null for success.
        private @Nullable Throwable cancellationFailure;

        /// Optional callback invoked reentrantly from start.
        private final @Nullable Runnable startCallback;

        /// Whether start emits successful onStop before returning.
        private final boolean synchronousStop;

        /// Number of cancellation invocations, including ones that throw.
        private final AtomicInteger cancelAttempts = new AtomicInteger();

        /// Number of legal cancellation requests received after start returned.
        private final AtomicInteger cancelCalls = new AtomicInteger();

        /// Whether a listener registration existed before start began.
        private volatile boolean listenerPresentAtStart;

        /// Whether start has returned and cancellation is therefore legal for this fake.
        private volatile boolean startReturned;

        /// Whether a terminal event has already been emitted.
        private boolean terminal;

        /// Creates one stopped controlled executor.
        ///
        /// @param task inert source task
        /// @param startGate optional start gate
        /// @param startFailure optional synchronous start failure
        /// @param cancelGate optional cancellation gate
        /// @param cancellationFailure optional one-shot cancellation failure
        /// @param startCallback optional reentrant start callback
        /// @param synchronousStop whether to emit successful onStop before returning
        private ControlledTaskExecutor(
                Task<?> task,
                @Nullable StartGate startGate,
                @Nullable RuntimeException startFailure,
                @Nullable CancelGate cancelGate,
                @Nullable Throwable cancellationFailure,
                @Nullable Runnable startCallback,
                boolean synchronousStop) {
            super(task);
            this.startGate = startGate;
            this.startFailure = startFailure;
            this.cancelGate = cancelGate;
            this.cancellationFailure = cancellationFailure;
            this.startCallback = startCallback;
            this.synchronousStop = synchronousStop;
        }

        /// Records listener ordering and optionally blocks before returning.
        ///
        /// @return this executor
        @Override
        public TaskExecutor start() {
            listenerPresentAtStart = hasTaskListeners();
            if (startGate != null) {
                startGate.enter();
            }
            startReturned = true;
            if (synchronousStop) {
                finish(true, null);
            }
            if (startCallback != null) {
                startCallback.run();
            }
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
        public void cancel() {
            if (!startReturned) {
                throw new IllegalStateException("Cancellation was forwarded before start returned");
            }
            synchronized (this) {
                if (terminal) {
                    throw new AssertionError("Cancellation was forwarded after terminal onStop");
                }
            }
            cancelAttempts.incrementAndGet();
            if (cancelGate != null) {
                cancelGate.enter();
            }
            @Nullable Throwable configuredFailure;
            synchronized (this) {
                configuredFailure = cancellationFailure;
                cancellationFailure = null;
            }
            if (configuredFailure != null) {
                throwUnchecked(configuredFailure);
            }
            cancelled = true;
            cancelCalls.incrementAndGet();
        }

        /// Configures the next cancellation invocation to throw one exact unchecked failure.
        ///
        /// @param failure failure to throw
        private synchronized void failNextCancellation(Throwable failure) {
            if (cancellationFailure != null) {
                throw new IllegalStateException("A cancellation failure is already pending");
            }
            cancellationFailure = failure;
        }

        /// Emits one terminal event with an optional exact failure object.
        ///
        /// @param success terminal success flag
        /// @param terminalFailure terminal failure, or null
        private void finish(boolean success, @Nullable Throwable terminalFailure) {
            synchronized (this) {
                if (!startReturned) {
                    throw new IllegalStateException("Cannot finish before start returns");
                }
                if (terminal) {
                    throw new IllegalStateException("Controlled executor already stopped");
                }
                terminal = true;
                failure = terminalFailure;
                exception = terminalFailure instanceof Exception exceptionValue
                        ? exceptionValue
                        : null;
            }
            notifyTaskListeners(listener -> listener.onStop(success, this));
        }

        /// Returns whether the source subscribed before invoking start.
        ///
        /// @return listener-order observation
        private boolean listenerPresentAtStart() {
            return listenerPresentAtStart;
        }

        /// Returns the cancellation request count.
        ///
        /// @return cancellation count
        private int cancelCalls() {
            return cancelCalls.get();
        }

        /// Returns the number of attempted cancellation invocations.
        ///
        /// @return cancellation-attempt count
        private int cancelAttempts() {
            return cancelAttempts.get();
        }
    }

    /// Two-latch gate that holds a controlled executor inside its start invocation.
    @NotNullByDefault
    private static final class StartGate {
        /// Signals that the controlled executor entered start.
        private final CountDownLatch entered = new CountDownLatch(1);

        /// Releases the blocked start invocation.
        private final CountDownLatch released = new CountDownLatch(1);

        /// Signals entry and waits for explicit release.
        private void enter() {
            entered.countDown();
            try {
                if (!released.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release controlled start");
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting to release controlled start", interruption);
            }
        }

        /// Waits until the executor has entered start.
        ///
        /// @return whether start was entered before the timeout
        private boolean awaitEntered() throws InterruptedException {
            return entered.await(5, TimeUnit.SECONDS);
        }

        /// Releases the executor's start invocation idempotently.
        private void release() {
            released.countDown();
        }
    }

    /// Two-latch gate that holds a controlled executor inside cancellation cleanup.
    @NotNullByDefault
    private static final class CancelGate {
        /// Signals that the controlled executor entered cancellation.
        private final CountDownLatch entered = new CountDownLatch(1);

        /// Releases the blocked cancellation invocation.
        private final CountDownLatch released = new CountDownLatch(1);

        /// Signals entry and waits for explicit release.
        private void enter() {
            entered.countDown();
            try {
                if (!released.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release controlled cancellation");
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                        "Interrupted while waiting to release controlled cancellation",
                        interruption);
            }
        }

        /// Waits until the executor has entered cancellation.
        ///
        /// @return whether cancellation was entered before the timeout
        private boolean awaitEntered() throws InterruptedException {
            return entered.await(5, TimeUnit.SECONDS);
        }

        /// Releases the executor cancellation idempotently.
        private void release() {
            released.countDown();
        }
    }

    /// Two-latch gate that holds one provider list lookup inside the source request barrier.
    @NotNullByDefault
    private static final class ProviderGate {
        /// Signals that the provider entered list lookup.
        private final CountDownLatch entered = new CountDownLatch(1);

        /// Releases the blocked provider invocation.
        private final CountDownLatch released = new CountDownLatch(1);

        /// Signals entry and waits for explicit release.
        private void enter() {
            entered.countDown();
            try {
                if (!released.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release controlled provider lookup");
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                        "Interrupted while waiting to release controlled provider lookup",
                        interruption);
            }
        }

        /// Waits until the provider lookup begins.
        ///
        /// @return whether lookup began before the timeout
        private boolean awaitEntered() throws InterruptedException {
            return entered.await(5, TimeUnit.SECONDS);
        }

        /// Releases the provider lookup idempotently.
        private void release() {
            released.countDown();
        }
    }
}
