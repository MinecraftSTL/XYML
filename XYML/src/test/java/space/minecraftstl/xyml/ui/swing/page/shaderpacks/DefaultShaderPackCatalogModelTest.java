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
package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.util.io.DeletionMode;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the asynchronous catalog model lifecycle and serialized mutation boundary.
@NotNullByDefault
final class DefaultShaderPackCatalogModelTest {
    /// Verifies initial scanning and successful mutation publication.
    @Test
    void publishesScanAndMutationSnapshots() {
        FakeAccess access = new FakeAccess();
        DefaultShaderPackCatalogModel model = new DefaultShaderPackCatalogModel(
                access,
                Runnable::run,
                "idle",
                "loading",
                "ready",
                "empty",
                "failed",
                "writing",
                "write failed");

        model.loadIfNeeded();

        assertEquals(ShaderPackCatalogStatus.READY, model.snapshot().status());
        assertEquals(1, model.snapshot().itemCount());
        assertEquals(Set.of(ShaderPackBackend.IRIS_OCULUS), model.snapshot().availableBackends());

        model.setShaderPackEnabled(Path.of("pack"), Set.of(ShaderPackBackend.IRIS_OCULUS), true).toCompletableFuture().join();

        assertTrue(access.enabled);
        assertEquals(ShaderPackCatalogWriteStatus.IDLE, model.snapshot().writeStatus());
        model.close();
    }

    /// Listener failures are isolated so later listeners run and the mutation Future reaches a terminal state.
    @Test
    void listenerFailureCannotStrandMutationFuture() {
        FakeAccess access = new FakeAccess();
        Executor swallowingExecutor = command -> {
            try {
                command.run();
            } catch (RuntimeException | Error ignored) {
                // Matches a worker executor, which cannot propagate task failures to the submitter.
            }
        };
        DefaultShaderPackCatalogModel model = model(access, swallowingExecutor);
        model.loadIfNeeded();
        AtomicInteger healthyCalls = new AtomicInteger();
        model.subscribe(change -> {
            if (change.currentValue().writeStatus() == ShaderPackCatalogWriteStatus.BUSY) {
                throw new AssertionError("broken listener");
            }
        });
        model.subscribe(change -> healthyCalls.incrementAndGet());

        CompletionStage<ShaderPackCatalogSnapshot> completion = model.setShaderPackEnabled(
                Path.of("pack"),
                Set.of(ShaderPackBackend.IRIS_OCULUS),
                true);

        assertTrue(completion.toCompletableFuture().isDone());
        completion.toCompletableFuture().join();
        assertTrue(healthyCalls.get() >= 2);
        assertEquals(ShaderPackCatalogWriteStatus.IDLE, model.snapshot().writeStatus());
        model.close();
    }

    /// A scan that began before a write cannot overwrite the mutation's newer terminal snapshot.
    @Test
    void staleRefreshCannotOverwriteCompletedMutation() throws InterruptedException {
        RacingAccess access = new RacingAccess();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        DefaultShaderPackCatalogModel model = model(access, executor);
        CountDownLatch initialReady = new CountDownLatch(1);
        model.subscribe(change -> {
            if (change.currentValue().status() == ShaderPackCatalogStatus.READY) {
                initialReady.countDown();
            }
        });
        try {
            model.loadIfNeeded();
            assertTrue(initialReady.await(5, TimeUnit.SECONDS));
            access.blockNextScan();
            model.refresh();
            assertTrue(access.staleScanStarted.await(5, TimeUnit.SECONDS));

            CompletionStage<ShaderPackCatalogSnapshot> mutation = model.setShaderPackEnabled(
                    access.path,
                    Set.of(ShaderPackBackend.IRIS_OCULUS),
                    true);
            assertFalse(mutation.toCompletableFuture().isDone());
            access.releaseStaleScan.countDown();

            mutation.toCompletableFuture().join();
            assertTrue(access.staleScanFinished.await(5, TimeUnit.SECONDS));
            assertTrue(model.snapshot().items().get(0).enabled());
        } finally {
            access.releaseStaleScan.countDown();
            model.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Creates a model with the standard test strings.
    private static DefaultShaderPackCatalogModel model(ShaderPackCatalogAccess access, Executor executor) {
        return new DefaultShaderPackCatalogModel(
                access,
                executor,
                "idle",
                "loading",
                "ready",
                "empty",
                "failed",
                "writing",
                "write failed");
    }

    /// Minimal synchronous access implementation used by model tests.
    @NotNullByDefault
    private static final class FakeAccess implements ShaderPackCatalogAccess {
        /// Path returned by the fixture.
        private final Path path = Path.of("pack").toAbsolutePath().normalize();

        /// Whether a mutation was observed.
        private boolean enabled;

        /// Returns one candidate.
        @Override
        public List<Path> loadIndex() {
            return List.of(path);
        }

        /// Returns one valid row.
        @Override
        public List<ShaderPackCatalogItem> loadItems(List<Path> paths) {
            return List.of(new ShaderPackCatalogItem(path, "pack", "pack", true, Set.of()));
        }

        /// Returns one available backend.
        @Override
        public Set<ShaderPackBackend> detectAvailableBackends() {
            return Set.of(ShaderPackBackend.IRIS_OCULUS);
        }

        /// Records a successful import.
        @Override
        public void importShaderPacks(List<Path> sources) {
        }

        /// Records a successful enable mutation.
        @Override
        public void setEnabled(Path path, Set<ShaderPackBackend> backends, boolean enabled) {
            this.enabled = enabled;
        }

        /// Records a successful deletion.
        @Override
        public void delete(Path path, DeletionMode mode) {
        }
    }

    /// Access fixture that returns a captured pre-write scan after the mutation has started.
    @NotNullByDefault
    private static final class RacingAccess implements ShaderPackCatalogAccess {
        /// Stable fixture path.
        private final Path path = Path.of("racing-pack").toAbsolutePath().normalize();

        /// Signals the deliberately stale scan entered storage.
        private final CountDownLatch staleScanStarted = new CountDownLatch(1);

        /// Releases the deliberately stale scan.
        private final CountDownLatch releaseStaleScan = new CountDownLatch(1);

        /// Signals the deliberately stale scan returned its captured row.
        private final CountDownLatch staleScanFinished = new CountDownLatch(1);

        /// Ensures exactly one post-arm scan is blocked.
        private final AtomicBoolean blockClaimed = new AtomicBoolean();

        /// Captured enabled state belonging to each scanning thread.
        private final ThreadLocal<Boolean> capturedEnabled = new ThreadLocal<>();

        /// Whether each scanning thread owns the stale blocked scan.
        private final ThreadLocal<Boolean> staleScan = ThreadLocal.withInitial(() -> false);

        /// Whether the backend currently selects the pack.
        private volatile boolean enabled;

        /// Whether the next unclaimed scan should block.
        private volatile boolean blockNextScan;

        /// Captures current state and optionally blocks one stale scan.
        @Override
        public List<Path> loadIndex() throws java.io.IOException {
            capturedEnabled.set(enabled);
            boolean shouldBlock = blockNextScan && blockClaimed.compareAndSet(false, true);
            staleScan.set(shouldBlock);
            if (shouldBlock) {
                staleScanStarted.countDown();
                await(releaseStaleScan);
            }
            return List.of(path);
        }

        /// Returns one row using the state captured when this scan loaded its index.
        @Override
        public List<ShaderPackCatalogItem> loadItems(List<Path> paths) {
            boolean captured = Boolean.TRUE.equals(capturedEnabled.get());
            ShaderPackCatalogItem item = new ShaderPackCatalogItem(
                    path,
                    "racing-pack",
                    "racing-pack",
                    true,
                    captured ? Set.of(ShaderPackBackend.IRIS_OCULUS) : Set.of());
            if (Boolean.TRUE.equals(staleScan.get())) {
                staleScanFinished.countDown();
            }
            capturedEnabled.remove();
            staleScan.remove();
            return List.of(item);
        }

        /// Returns one available backend.
        @Override
        public Set<ShaderPackBackend> detectAvailableBackends() {
            return Set.of(ShaderPackBackend.IRIS_OCULUS);
        }

        /// Accepts imports unused by this fixture.
        @Override
        public void importShaderPacks(List<Path> sources) {
        }

        /// Changes the backend-selected state.
        @Override
        public void setEnabled(Path path, Set<ShaderPackBackend> backends, boolean enabled) {
            this.enabled = enabled;
        }

        /// Accepts deletions unused by this fixture.
        @Override
        public void delete(Path path, DeletionMode mode) {
        }

        /// Arms one stale blocked scan.
        private void blockNextScan() {
            blockNextScan = true;
        }

        /// Waits for one fixture latch while preserving interruption.
        private static void await(CountDownLatch latch) throws java.io.IOException {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new java.io.IOException("timed out waiting for shader test latch");
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("interrupted while waiting for shader test latch", interruption);
            }
        }
    }
}
