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
package space.minecraftstl.xyml.ui.swing.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.task.FileDownloadTask.IntegrityCheck;
import space.minecraftstl.xyml.upgrade.RemoteVersion;
import space.minecraftstl.xyml.upgrade.UpdateChannel;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies update-check scheduling, deduplication, immutable state, and closure barriers.
@NotNullByDefault
class SwingUpdateCheckServiceTest {
    /// Runs equal requests once on a worker and publishes both checking and success transitions.
    @Test
    void deduplicatesEqualConcurrentChecksOnBackgroundExecutor() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch sourceEntered = new CountDownLatch(1);
        CountDownLatch releaseSource = new CountDownLatch(1);
        AtomicInteger sourceCalls = new AtomicInteger();
        AtomicReference<@Nullable Thread> sourceThread = new AtomicReference<>();
        List<UpdateCheckSnapshot.Status> statuses = new CopyOnWriteArrayList<>();
        SwingUpdateCheckService service = new SwingUpdateCheckService(
                request -> {
                    sourceCalls.incrementAndGet();
                    sourceThread.set(Thread.currentThread());
                    sourceEntered.countDown();
                    await(releaseSource);
                    return remote("2.0", request.channel(), false);
                },
                remoteVersion -> true,
                worker);
        try (Subscription ignored = service.subscribe(change -> {
            @Nullable UpdateCheckSnapshot snapshot = change.currentValue();
            if (snapshot != null) {
                statuses.add(snapshot.status());
            }
        })) {
            UpdateCheckRequest request = new UpdateCheckRequest(UpdateChannel.STABLE, false);
            CompletionStage<UpdateCheckResult> first = service.check(request);
            assertTrue(sourceEntered.await(5, TimeUnit.SECONDS));
            CompletionStage<UpdateCheckResult> duplicate = service.check(request);

            assertSame(first, duplicate);
            assertEquals(1, sourceCalls.get());
            assertFalse(Thread.currentThread().equals(sourceThread.get()));

            releaseSource.countDown();
            UpdateCheckResult result = first.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("2.0", result.remoteVersion().version());
            assertEquals(
                    List.of(UpdateCheckSnapshot.Status.CHECKING, UpdateCheckSnapshot.Status.SUCCEEDED),
                    statuses);
        } finally {
            releaseSource.countDown();
            service.close();
            worker.shutdownNow();
        }
    }

    /// Serializes distinct requests even when the supplied executor has parallel capacity.
    @Test
    void serializesDistinctRequests() throws Exception {
        ExecutorService worker = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger sourceCalls = new AtomicInteger();
        AtomicInteger concurrentSources = new AtomicInteger();
        AtomicInteger maximumConcurrentSources = new AtomicInteger();
        SwingUpdateCheckService service = new SwingUpdateCheckService(
                request -> {
                    int concurrent = concurrentSources.incrementAndGet();
                    maximumConcurrentSources.accumulateAndGet(concurrent, Math::max);
                    int call = sourceCalls.incrementAndGet();
                    try {
                        if (call == 1) {
                            firstEntered.countDown();
                            await(releaseFirst);
                        }
                        return remote("2." + call, request.channel(), false);
                    } finally {
                        concurrentSources.decrementAndGet();
                    }
                },
                remoteVersion -> true,
                worker);
        try {
            CompletionStage<UpdateCheckResult> stable = service.check(
                    new UpdateCheckRequest(UpdateChannel.STABLE, false));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            CompletionStage<UpdateCheckResult> preview = service.check(
                    new UpdateCheckRequest(UpdateChannel.STABLE, true));

            assertEquals(1, sourceCalls.get());
            releaseFirst.countDown();
            stable.toCompletableFuture().get(5, TimeUnit.SECONDS);
            preview.toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(2, sourceCalls.get());
            assertEquals(1, maximumConcurrentSources.get());
        } finally {
            releaseFirst.countDown();
            service.close();
            worker.shutdownNow();
        }
    }

    /// Retains the last successful result when a later attempt fails with its original exception.
    @Test
    void failureRetainsLastSuccessfulResult() {
        AtomicInteger sourceCalls = new AtomicInteger();
        IOException expectedFailure = new IOException("offline");
        Instant fixedTime = Instant.parse("2026-07-24T08:00:00Z");
        SwingUpdateCheckService service = new SwingUpdateCheckService(
                request -> {
                    if (sourceCalls.getAndIncrement() == 0) {
                        return remote("2.0", request.channel(), false);
                    }
                    throw expectedFailure;
                },
                remoteVersion -> true,
                Runnable::run,
                Clock.fixed(fixedTime, ZoneOffset.UTC),
                () -> {
                });
        try {
            UpdateCheckResult successful = service.check(
                    new UpdateCheckRequest(UpdateChannel.STABLE, false))
                    .toCompletableFuture()
                    .join();
            CompletionStage<UpdateCheckResult> failed = service.check(
                    new UpdateCheckRequest(UpdateChannel.STABLE, true));

            CompletionException completionFailure = assertThrows(
                    CompletionException.class,
                    () -> failed.toCompletableFuture().join());
            assertSame(expectedFailure, completionFailure.getCause());

            UpdateCheckSnapshot snapshot = service.snapshot();
            assertEquals(UpdateCheckSnapshot.Status.FAILED, snapshot.status());
            assertSame(successful, snapshot.lastSuccessfulResult().orElseThrow());
            UpdateCheckSnapshot.Failure failure = snapshot.lastFailure().orElseThrow();
            assertEquals(IOException.class.getName(), failure.failureType());
            assertEquals("offline", failure.message());
            assertEquals(fixedTime, failure.failedAt());
        } finally {
            service.close();
        }
    }

    /// Gates a late blocking source result after service closure.
    @Test
    void closeCancelsActiveStageAndRejectsLateResult() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch sourceEntered = new CountDownLatch(1);
        CountDownLatch releaseSource = new CountDownLatch(1);
        SwingUpdateCheckService service = new SwingUpdateCheckService(
                request -> {
                    sourceEntered.countDown();
                    awaitIgnoringInterruption(releaseSource);
                    return remote("2.0", request.channel(), false);
                },
                remoteVersion -> true,
                worker);
        CompletionStage<UpdateCheckResult> completion = service.check(
                new UpdateCheckRequest(UpdateChannel.STABLE, false));
        assertTrue(sourceEntered.await(5, TimeUnit.SECONDS));

        service.close();
        releaseSource.countDown();

        assertThrows(CancellationException.class, () -> completion.toCompletableFuture().join());
        assertEquals(UpdateCheckSnapshot.Status.CLOSED, service.snapshot().status());
        assertThrows(
                IllegalStateException.class,
                () -> service.check(new UpdateCheckRequest(UpdateChannel.STABLE, false)));
        worker.shutdownNow();
    }

    /// Builds one deterministic remote-version fixture.
    ///
    /// @param version remote version string
    /// @param channel remote channel
    /// @param force whether the release is mandatory
    /// @return remote-version fixture
    private static RemoteVersion remote(String version, UpdateChannel channel, boolean force) {
        return new RemoteVersion(
                channel,
                version,
                "https://example.test/xyml.jar",
                RemoteVersion.Type.JAR,
                new IntegrityCheck("SHA-1", "0123456789abcdef"),
                false,
                force);
    }

    /// Waits for a test latch and preserves interruption as an assertion failure.
    ///
    /// @param latch latch to await
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test latch", failure);
        }
    }

    /// Waits through executor interruption so a deliberately late result can reach the close gate.
    ///
    /// @param latch latch to await
    private static void awaitIgnoringInterruption(CountDownLatch latch) {
        boolean interrupted = false;
        while (latch.getCount() != 0L) {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
