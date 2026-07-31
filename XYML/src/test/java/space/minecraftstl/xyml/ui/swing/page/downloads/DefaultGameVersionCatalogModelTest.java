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
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests lazy loading, generation ownership, filtering, and exact viewport slicing.
@NotNullByDefault
public final class DefaultGameVersionCatalogModelTest {
    /// Localized deterministic text used by every model fixture.
    private static final GameVersionCatalogStatusStrings STATUS_STRINGS =
            new GameVersionCatalogStatusStrings("idle", "loading", "ready", "empty", "failed");

    /// Maximum time allotted to one deterministic concurrency checkpoint.
    private static final long CONCURRENCY_TIMEOUT_SECONDS = 5L;

    /// Verifies construction is lazy and an empty success does not invent a content revision.
    @Test
    public void remainsIdleUntilLazyLoadAndHandlesEmptySuccess() {
        ControlledSource source = new ControlledSource();
        DefaultGameVersionCatalogModel model = new DefaultGameVersionCatalogModel(source, STATUS_STRINGS);

        ChoicePage<GameVersionCatalogItem> initialPage = model.load(
                new IndexRange(0, 8),
                new LoadCancellation()).toCompletableFuture().join();
        assertAll(
                () -> assertEquals(0, source.requestCount()),
                () -> assertEquals(GameVersionCatalogStatus.IDLE, model.snapshot().status()),
                () -> assertEquals(GameVersionFilter.RELEASE, model.snapshot().filter()),
                () -> assertEquals(OptionalInt.of(0), model.exactItemCount()),
                () -> assertEquals(new IndexRange(0, 0), initialPage.range()));

        model.loadIfNeeded();
        model.loadIfNeeded();
        assertAll(
                () -> assertEquals(1, source.requestCount()),
                () -> assertEquals(GameVersionCatalogStatus.LOADING, model.snapshot().status()),
                () -> assertFalse(model.snapshot().refreshEnabled()));

        source.request(0).complete(List.of());
        assertAll(
                () -> assertEquals(GameVersionCatalogStatus.READY, model.snapshot().status()),
                () -> assertEquals("empty", model.snapshot().statusText()),
                () -> assertEquals(0, model.snapshot().itemCount()),
                () -> assertEquals(0L, model.snapshot().contentRevision()),
                () -> assertTrue(model.snapshot().refreshEnabled()),
                () -> assertFalse(model.snapshot().listEnabled()));
        model.close();
    }

    /// Verifies successful source order, exact count, metadata, and unclamped requested size independence.
    @Test
    public void publishesSuccessAndSlicesExactRange() {
        ControlledSource source = new ControlledSource();
        DefaultGameVersionCatalogModel model = new DefaultGameVersionCatalogModel(source, STATUS_STRINGS);
        GameVersionCatalogItem release = new GameVersionCatalogItem(
                "1.21.4",
                GameVersionKind.RELEASE,
                Optional.of(Instant.parse("2024-12-03T10:00:00Z")));
        GameVersionCatalogItem snapshot = item("25w02a", GameVersionKind.SNAPSHOT);
        GameVersionCatalogItem old = item("rd-132211", GameVersionKind.OLD);

        model.setFilter(GameVersionFilter.ALL);
        model.loadIfNeeded();
        source.request(0).complete(List.of(release, snapshot, old));
        ChoicePage<GameVersionCatalogItem> middleAndEnd = model.load(
                new IndexRange(1, 30),
                new LoadCancellation()).toCompletableFuture().join();
        ChoicePage<GameVersionCatalogItem> prefix = model.load(
                new IndexRange(0, 2),
                new LoadCancellation()).toCompletableFuture().join();

        assertAll(
                () -> assertEquals(GameVersionCatalogStatus.READY, model.snapshot().status()),
                () -> assertEquals("ready", model.snapshot().statusText()),
                () -> assertEquals(3, model.snapshot().itemCount()),
                () -> assertEquals(1L, model.snapshot().contentRevision()),
                () -> assertEquals(new IndexRange(1, 3), middleAndEnd.range()),
                () -> assertEquals(List.of(snapshot, old), middleAndEnd.items()),
                () -> assertEquals(OptionalInt.of(3), middleAndEnd.exactItemCount()),
                () -> assertTrue(middleAndEnd.endOfData()),
                () -> assertFalse(prefix.endOfData()),
                () -> assertEquals(Optional.of(Instant.parse("2024-12-03T10:00:00Z")),
                        release.releaseDate()));
        model.close();
    }

    /// Verifies failure state retains a retry path and a later generation can recover.
    @Test
    public void retriesFailedLoad() {
        ControlledSource source = new ControlledSource();
        DefaultGameVersionCatalogModel model = new DefaultGameVersionCatalogModel(source, STATUS_STRINGS);

        model.loadIfNeeded();
        source.request(0).fail(new IOException("catalog unavailable"));
        assertAll(
                () -> assertEquals(GameVersionCatalogStatus.FAILED, model.snapshot().status()),
                () -> assertEquals("failed", model.snapshot().statusText()),
                () -> assertTrue(model.snapshot().refreshEnabled()));

        model.refresh();
        source.request(1).complete(List.of(item("1.20.6", GameVersionKind.RELEASE)));
        assertAll(
                () -> assertEquals(2, source.requestCount()),
                () -> assertEquals(GameVersionCatalogStatus.READY, model.snapshot().status()),
                () -> assertEquals(1, model.snapshot().itemCount()),
                () -> assertEquals(1L, model.snapshot().contentRevision()));
        model.close();
    }

    /// Verifies a consecutive refresh cancels and supersedes the previous source generation.
    @Test
    public void discardsSupersededRefreshResults() {
        ControlledSource source = new ControlledSource();
        DefaultGameVersionCatalogModel model = new DefaultGameVersionCatalogModel(source, STATUS_STRINGS);

        model.loadIfNeeded();
        SourceRequest first = source.request(0);
        model.refresh();
        SourceRequest second = source.request(1);
        first.complete(List.of(item("obsolete", GameVersionKind.OLD)));

        assertAll(
                () -> assertTrue(first.cancellation().isCancelled()),
                () -> assertEquals(GameVersionCatalogStatus.LOADING, model.snapshot().status()),
                () -> assertEquals(0, model.snapshot().itemCount()),
                () -> assertEquals(0L, model.snapshot().contentRevision()));

        GameVersionCatalogItem current = item("1.21.5", GameVersionKind.RELEASE);
        second.complete(List.of(current));
        ChoicePage<GameVersionCatalogItem> page = model.load(
                new IndexRange(0, 1),
                new LoadCancellation()).toCompletableFuture().join();
        assertAll(
                () -> assertEquals(GameVersionCatalogStatus.READY, model.snapshot().status()),
                () -> assertEquals(List.of(current), page.items()),
                () -> assertEquals(1L, model.snapshot().contentRevision()));
        model.close();
    }

    /// Verifies close cancels active work and rejects its eventual result.
    @Test
    public void ignoresLateResultAfterClose() {
        ControlledSource source = new ControlledSource();
        DefaultGameVersionCatalogModel model = new DefaultGameVersionCatalogModel(source, STATUS_STRINGS);

        model.loadIfNeeded();
        SourceRequest request = source.request(0);
        model.close();
        request.complete(List.of(item("late", GameVersionKind.RELEASE)));

        assertAll(
                () -> assertTrue(request.cancellation().isCancelled()),
                () -> assertEquals(GameVersionCatalogStatus.LOADING, model.snapshot().status()),
                () -> assertEquals(0, model.snapshot().itemCount()),
                () -> assertEquals(0L, model.snapshot().contentRevision()),
                () -> assertThrows(IllegalStateException.class, model::refresh),
                () -> assertThrows(IllegalStateException.class,
                        () -> model.subscribe(ignored -> { })));
    }

    /// Verifies kind and case-insensitive query filtering preserve hidden stable selection identity.
    @Test
    public void filtersSearchesAndRestoresStableSelection() {
        ControlledSource source = new ControlledSource();
        DefaultGameVersionCatalogModel model = new DefaultGameVersionCatalogModel(source, STATUS_STRINGS);
        GameVersionCatalogItem release = item("Release-1.21", GameVersionKind.RELEASE);
        GameVersionCatalogItem snapshot = item("Snapshot-25w02a", GameVersionKind.SNAPSHOT);
        GameVersionCatalogItem april = item("April-2.0", GameVersionKind.APRIL_FOOLS);
        GameVersionCatalogItem old = item("Old-alpha", GameVersionKind.OLD);

        model.loadIfNeeded();
        source.request(0).complete(List.of(release, snapshot, april, old));
        model.selectVersion(snapshot.versionId());
        assertAll(
                () -> assertEquals(GameVersionFilter.RELEASE, model.snapshot().filter()),
                () -> assertEquals(1, model.snapshot().itemCount()),
                () -> assertEquals(OptionalInt.empty(), model.snapshot().selectedIndex()));

        model.setFilter(GameVersionFilter.ALL);
        assertAll(
                () -> assertEquals(4, model.snapshot().itemCount()),
                () -> assertEquals(OptionalInt.of(1), model.snapshot().selectedIndex()),
                () -> assertEquals(2L, model.snapshot().contentRevision()));

        model.setFilter(GameVersionFilter.RELEASE);
        model.setQuery("rElEaSe");
        assertEquals(3L, model.snapshot().contentRevision());
        model.setQuery("");
        model.setFilter(GameVersionFilter.ALL);
        assertAll(
                () -> assertEquals(4, model.snapshot().itemCount()),
                () -> assertEquals(OptionalInt.of(1), model.snapshot().selectedIndex()),
                () -> assertEquals(4L, model.snapshot().contentRevision()));

        model.setQuery("sNaPsHoT");
        model.setFilter(GameVersionFilter.SNAPSHOT);
        assertAll(
                () -> assertEquals(1, model.snapshot().itemCount()),
                () -> assertEquals(OptionalInt.of(0), model.snapshot().selectedIndex()),
                () -> assertEquals(5L, model.snapshot().contentRevision()),
                () -> assertEquals("sNaPsHoT", model.snapshot().query()),
                () -> assertEquals(GameVersionFilter.SNAPSHOT, model.snapshot().filter()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> model.selectVersion("unknown")));

        model.setQuery("missing");
        assertAll(
                () -> assertEquals(0, model.snapshot().itemCount()),
                () -> assertEquals("empty", model.snapshot().statusText()),
                () -> assertEquals(OptionalInt.empty(), model.snapshot().selectedIndex()));
        model.setQuery("");
        assertEquals(OptionalInt.of(0), model.snapshot().selectedIndex());
        model.close();
    }

    /// Verifies local range clipping and cancellation without introducing source page assumptions.
    @Test
    public void clipsRangesAndHonorsCancellation() {
        ControlledSource source = new ControlledSource();
        DefaultGameVersionCatalogModel model = new DefaultGameVersionCatalogModel(source, STATUS_STRINGS);
        GameVersionCatalogItem first = item("one", GameVersionKind.RELEASE);
        GameVersionCatalogItem second = item("two", GameVersionKind.SNAPSHOT);
        GameVersionCatalogItem third = item("three", GameVersionKind.OLD);
        model.setFilter(GameVersionFilter.ALL);
        model.loadIfNeeded();
        source.request(0).complete(List.of(first, second, third));

        ChoicePage<GameVersionCatalogItem> clipped = model.load(
                new IndexRange(2, 200),
                new LoadCancellation()).toCompletableFuture().join();
        LoadCancellation cancelled = new LoadCancellation();
        cancelled.cancel();
        CompletionStage<ChoicePage<GameVersionCatalogItem>> cancelledLoad = model.load(
                new IndexRange(0, 3),
                cancelled);

        assertAll(
                () -> assertEquals(new IndexRange(2, 3), clipped.range()),
                () -> assertEquals(List.of(third), clipped.items()),
                () -> assertEquals(OptionalInt.of(3), clipped.exactItemCount()),
                () -> assertThrows(CancellationException.class,
                        () -> cancelledLoad.toCompletableFuture().join()));
        model.close();
    }

    /// Verifies nested transitions supersede the outer event before later listeners see stale state.
    @Test
    public void ordersReentrantListenerTransitions() {
        ControlledSource source = new ControlledSource();
        DefaultGameVersionCatalogModel model = new DefaultGameVersionCatalogModel(source, STATUS_STRINGS);
        AtomicBoolean replacedQuery = new AtomicBoolean();
        List<String> readyQueriesSeenByLaterListener = new ArrayList<>();
        model.subscribe(change -> {
            GameVersionCatalogSnapshot current = change.currentValue();
            if (current != null
                    && current.status() == GameVersionCatalogStatus.READY
                    && replacedQuery.compareAndSet(false, true)) {
                model.setQuery("release");
            }
        });
        model.subscribe(change -> {
            GameVersionCatalogSnapshot current = change.currentValue();
            if (current != null && current.status() == GameVersionCatalogStatus.READY) {
                readyQueriesSeenByLaterListener.add(current.query());
            }
        });

        model.loadIfNeeded();
        source.request(0).complete(List.of(
                item("release", GameVersionKind.RELEASE),
                item("snapshot", GameVersionKind.SNAPSHOT)));

        assertAll(
                () -> assertEquals("release", model.snapshot().query()),
                () -> assertEquals(List.of("release"), readyQueriesSeenByLaterListener));
        model.close();
    }

    /// Verifies a listener that closes reentrantly prevents all later listener callbacks.
    @Test
    public void stopsPublicationAfterReentrantClose() {
        ControlledSource source = new ControlledSource();
        DefaultGameVersionCatalogModel model = new DefaultGameVersionCatalogModel(source, STATUS_STRINGS);
        AtomicInteger callbacksAfterClose = new AtomicInteger();
        model.subscribe(change -> {
            GameVersionCatalogSnapshot current = change.currentValue();
            if (current != null && current.status() == GameVersionCatalogStatus.READY) {
                model.close();
            }
        });
        model.subscribe(change -> {
            GameVersionCatalogSnapshot current = change.currentValue();
            if (current != null && current.status() == GameVersionCatalogStatus.READY) {
                callbacksAfterClose.incrementAndGet();
            }
        });

        model.loadIfNeeded();
        source.request(0).complete(List.of(item("release", GameVersionKind.RELEASE)));

        assertAll(
                () -> assertEquals(0, callbacksAfterClose.get()),
                () -> assertEquals(GameVersionCatalogStatus.READY, model.snapshot().status()),
                () -> assertThrows(IllegalStateException.class, model::refresh));
    }

    /// Verifies synchronous source completion cannot invert source and publication lock order.
    @Test
    public void completedSourceAllowsPublicationListenerToCloseReentrantly() {
        CountDownLatch sourceEntered = new CountDownLatch(1);
        GameVersionCatalogSource source = cancellation -> CompletableFuture.completedFuture(
                List.of(item("release", GameVersionKind.RELEASE)))
                .whenComplete((items, failure) -> sourceEntered.countDown());
        DefaultGameVersionCatalogModel model = new DefaultGameVersionCatalogModel(source, STATUS_STRINGS);
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch publicationReturned = new CountDownLatch(1);
        CountDownLatch refreshReturned = new CountDownLatch(1);
        AtomicReference<@Nullable Thread> refreshThreadReference = new AtomicReference<>();
        AtomicReference<@Nullable Throwable> publicationFailure = new AtomicReference<>();
        AtomicReference<@Nullable Throwable> refreshFailure = new AtomicReference<>();
        model.subscribe(change -> {
            GameVersionCatalogSnapshot current = change.currentValue();
            if (current != null && current.query().equals("hold-publication")) {
                listenerEntered.countDown();
                awaitLatch(sourceEntered, "synchronous source did not complete");
                Thread refreshThread = Objects.requireNonNull(
                        refreshThreadReference.get(),
                        "refresh thread was not installed");
                awaitThreadBlocked(refreshThread, "refresh did not block on the held publication monitor");
                model.close();
            }
        });
        Thread publicationThread = daemonThread(
                "game-version-held-publication",
                () -> model.setQuery("hold-publication"),
                publicationFailure,
                publicationReturned);
        Thread refreshThread = daemonThread(
                "game-version-synchronous-refresh",
                model::refresh,
                refreshFailure,
                refreshReturned);
        refreshThreadReference.set(refreshThread);

        publicationThread.start();
        awaitLatch(listenerEntered, "query transition did not enter its publication listener");
        refreshThread.start();

        awaitLatch(publicationReturned, "publication listener deadlocked while closing the model");
        awaitLatch(refreshReturned, "synchronous source refresh did not return after close");
        assertAll(
                () -> assertNull(publicationFailure.get()),
                () -> assertNull(refreshFailure.get()),
                () -> assertThrows(IllegalStateException.class, model::refresh));
    }

    /// Verifies every concurrent close caller waits for an in-flight source invocation barrier.
    @Test
    public void concurrentCloseCallersBothCrossSourceInvocationBarrier() {
        CountDownLatch sourceEntered = new CountDownLatch(1);
        CountDownLatch releaseSource = new CountDownLatch(1);
        CountDownLatch cancellationObserved = new CountDownLatch(1);
        GameVersionCatalogSource source = cancellation -> {
            sourceEntered.countDown();
            awaitReleaseWhileObservingCancellation(
                    releaseSource,
                    cancellation,
                    cancellationObserved);
            return CompletableFuture.completedFuture(List.of());
        };
        DefaultGameVersionCatalogModel model = new DefaultGameVersionCatalogModel(source, STATUS_STRINGS);
        CountDownLatch loadReturned = new CountDownLatch(1);
        CountDownLatch firstCloseReturned = new CountDownLatch(1);
        CountDownLatch secondCloseReturned = new CountDownLatch(1);
        AtomicReference<@Nullable Throwable> loadFailure = new AtomicReference<>();
        AtomicReference<@Nullable Throwable> firstCloseFailure = new AtomicReference<>();
        AtomicReference<@Nullable Throwable> secondCloseFailure = new AtomicReference<>();
        Thread loadThread = daemonThread(
                "game-version-blocked-source",
                model::loadIfNeeded,
                loadFailure,
                loadReturned);
        Thread firstCloseThread = daemonThread(
                "game-version-first-close",
                model::close,
                firstCloseFailure,
                firstCloseReturned);
        Thread secondCloseThread = daemonThread(
                "game-version-second-close",
                model::close,
                secondCloseFailure,
                secondCloseReturned);

        loadThread.start();
        try {
            awaitLatch(sourceEntered, "source invocation did not enter its blocking section");
            firstCloseThread.start();
            awaitLatch(cancellationObserved, "first close did not cancel the active source invocation");
            secondCloseThread.start();
            awaitBlockedOrReturned(secondCloseThread, secondCloseReturned);
            assertAll(
                    () -> assertEquals(Thread.State.BLOCKED, secondCloseThread.getState()),
                    () -> assertEquals(1L, secondCloseReturned.getCount(),
                            "second close returned before crossing the source barrier"));
        } finally {
            releaseSource.countDown();
        }

        awaitLatch(loadReturned, "blocked source invocation did not return after release");
        awaitLatch(firstCloseReturned, "first close did not cross the released source barrier");
        awaitLatch(secondCloseReturned, "second close did not cross the released source barrier");
        assertAll(
                () -> assertNull(loadFailure.get()),
                () -> assertNull(firstCloseFailure.get()),
                () -> assertNull(secondCloseFailure.get()));
    }

    /// Verifies one runtime-failing observer cannot suppress a later observer.
    @Test
    public void isolatesRuntimeListenerFailures() {
        ControlledSource source = new ControlledSource();
        DefaultGameVersionCatalogModel model = new DefaultGameVersionCatalogModel(source, STATUS_STRINGS);
        AtomicInteger laterCallbacks = new AtomicInteger();
        Thread thread = Thread.currentThread();
        @Nullable Thread.UncaughtExceptionHandler previousHandler = thread.getUncaughtExceptionHandler();
        thread.setUncaughtExceptionHandler((ignoredThread, ignoredFailure) -> { });
        try {
            model.subscribe(change -> {
                if (change.currentValue() != null
                        && change.currentValue().status() == GameVersionCatalogStatus.READY) {
                    throw new IllegalStateException("observer failed");
                }
            });
            model.subscribe(change -> {
                if (change.currentValue() != null
                        && change.currentValue().status() == GameVersionCatalogStatus.READY) {
                    laterCallbacks.incrementAndGet();
                }
            });

            model.loadIfNeeded();
            source.request(0).complete(List.of(item("release", GameVersionKind.RELEASE)));
            assertEquals(1, laterCallbacks.get());
        } finally {
            thread.setUncaughtExceptionHandler(previousHandler);
            model.close();
        }
    }

    /// Verifies a synchronous source Error first leaves a retryable failed state, then propagates unchanged.
    @Test
    public void sourceErrorFailsGenerationBeforeRethrow() {
        AssertionError sourceError = new AssertionError("source invariant failed");
        GameVersionCatalogSource source = cancellation -> {
            throw sourceError;
        };
        DefaultGameVersionCatalogModel model = new DefaultGameVersionCatalogModel(source, STATUS_STRINGS);

        AssertionError thrown = assertThrows(AssertionError.class, model::loadIfNeeded);

        assertAll(
                () -> assertSame(sourceError, thrown),
                () -> assertEquals(GameVersionCatalogStatus.FAILED, model.snapshot().status()),
                () -> assertEquals("failed", model.snapshot().statusText()),
                () -> assertTrue(model.snapshot().refreshEnabled()));
        model.close();
    }

    /// Creates one catalog item without an upstream release timestamp.
    ///
    /// @param versionId stable version ID
    /// @param kind version classification
    /// @return immutable test item
    private static GameVersionCatalogItem item(String versionId, GameVersionKind kind) {
        return new GameVersionCatalogItem(versionId, kind, Optional.empty());
    }

    /// Creates a daemon thread that captures every failure and always signals completion.
    ///
    /// @param name diagnostic thread name
    /// @param action thread action
    /// @param failure captured failure destination
    /// @param completion completion signal
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

    /// Waits for one concurrency checkpoint with a finite failure bound.
    ///
    /// @param latch checkpoint signal
    /// @param timeoutMessage assertion message when the checkpoint is not reached
    private static void awaitLatch(CountDownLatch latch, String timeoutMessage) {
        try {
            assertTrue(
                    latch.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    timeoutMessage);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for concurrency checkpoint", interrupted);
        }
    }

    /// Keeps a blocking source invocation alive while exposing when close requests cancellation.
    ///
    /// @param release source-release signal
    /// @param cancellation model-owned cancellation signal
    /// @param cancellationObserved signal raised after cancellation becomes visible
    private static void awaitReleaseWhileObservingCancellation(
            CountDownLatch release,
            LoadCancellation cancellation,
            CountDownLatch cancellationObserved) {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(CONCURRENCY_TIMEOUT_SECONDS);
        try {
            while (!release.await(10L, TimeUnit.MILLISECONDS)) {
                if (cancellation.isCancelled()) {
                    cancellationObserved.countDown();
                }
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError("Timed out before the blocking source was released");
                }
            }
            if (cancellation.isCancelled()) {
                cancellationObserved.countDown();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Blocking source invocation was interrupted", interrupted);
        }
    }

    /// Waits until the second close is observably blocked or has incorrectly returned.
    ///
    /// @param closeThread second close thread
    /// @param closeReturned second close completion signal
    private static void awaitBlockedOrReturned(
            Thread closeThread,
            CountDownLatch closeReturned) {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(CONCURRENCY_TIMEOUT_SECONDS);
        while (closeReturned.getCount() != 0L
                && closeThread.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        assertTrue(
                closeReturned.getCount() == 0L || closeThread.getState() == Thread.State.BLOCKED,
                "second close neither blocked nor returned within the timeout");
    }

    /// Waits until one thread is blocked on a monitor with a finite failure bound.
    ///
    /// @param thread thread expected to block
    /// @param timeoutMessage assertion message when the thread does not block
    private static void awaitThreadBlocked(Thread thread, String timeoutMessage) {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(CONCURRENCY_TIMEOUT_SECONDS);
        while (thread.getState() != Thread.State.BLOCKED
                && thread.getState() != Thread.State.TERMINATED
                && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        assertEquals(Thread.State.BLOCKED, thread.getState(), timeoutMessage);
    }

    /// Source whose requests are completed explicitly by each test.
    @NotNullByDefault
    private static final class ControlledSource implements GameVersionCatalogSource {
        /// Requests captured in invocation order.
        private final List<SourceRequest> requests = new ArrayList<>();

        /// Captures one source request without completing it.
        ///
        /// @param cancellation model-owned cancellation signal
        /// @return test-controlled result stage
        @Override
        public synchronized CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> load(
                LoadCancellation cancellation) {
            SourceRequest request = new SourceRequest(
                    cancellation,
                    new CompletableFuture<>());
            requests.add(request);
            return request.result();
        }

        /// Returns one captured request.
        ///
        /// @param index request index
        /// @return captured request
        private synchronized SourceRequest request(int index) {
            return requests.get(index);
        }

        /// Returns the number of captured source requests.
        ///
        /// @return captured request count
        private synchronized int requestCount() {
            return requests.size();
        }
    }

    /// One test-controlled source request and its cancellation signal.
    ///
    /// @param cancellation model-owned cooperative cancellation signal
    /// @param result explicitly completed source result
    @NotNullByDefault
    private record SourceRequest(
            LoadCancellation cancellation,
            CompletableFuture<@Unmodifiable List<GameVersionCatalogItem>> result) {
        /// Completes this request successfully.
        ///
        /// @param items immutable source items
        private void complete(@Unmodifiable List<GameVersionCatalogItem> items) {
            result.complete(items);
        }

        /// Completes this request exceptionally.
        ///
        /// @param failure source failure
        private void fail(Throwable failure) {
            result.completeExceptionally(failure);
        }
    }
}
