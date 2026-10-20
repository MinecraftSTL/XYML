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
package space.minecraftstl.xyml.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that the real executor reports resource-waiting tasks before a lease is granted.
@NotNullByDefault
public final class AsyncTaskExecutionRegistryIntegrationTest {
    /// Maximum time allowed for one asynchronous lifecycle assertion.
    private static final long TIMEOUT_SECONDS = 5L;

    /// A resource-waiting task is visible as WAITING and becomes CANCELLED through its top-level execution ID.
    @Test
    public void resourceWaitingTaskIsVisibleAndCancellationUpdatesItsDetail() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        TaskResource resource = TaskResource.downloadTarget(
                Path.of("build", "task-registry-waiting-" + UUID.randomUUID() + ".jar"));
        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        AtomicBoolean waiterBodyStarted = new AtomicBoolean();
        Task<?> holder = Task.runAsync("holder", () -> {
            holderStarted.countDown();
            if (!releaseHolder.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("holder release timed out");
            }
        }).setResources(resource);
        Task<?> waiter = Task.runAsync("waiter", () -> waiterBodyStarted.set(true)).setResources(resource);
        AsyncTaskExecutor holderExecutor = new AsyncTaskExecutor(holder, manager, registry);
        AsyncTaskExecutor waiterExecutor = new AsyncTaskExecutor(waiter, manager, registry);
        holderExecutor.setTaskExecutionPresentation("holder workflow", true);
        waiterExecutor.setTaskExecutionPresentation("waiter workflow", true);
        AtomicBoolean cancellationVisibleToLegacyListener = new AtomicBoolean();
        waiterExecutor.subscribeTaskListener(new TaskListener() {
            @Override
            public void onStop(boolean success, TaskExecutor executor) {
                cancellationVisibleToLegacyListener.set(!success && executor.isCancelled());
            }
        });

        CompletableFuture<Boolean> holderResult = CompletableFuture.supplyAsync(holderExecutor::test);
        CompletableFuture<Boolean> waiterResult = null;
        try {
            assertTrue(holderStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            waiterResult = CompletableFuture.supplyAsync(waiterExecutor::test);
            awaitCondition(() -> find(registry, "waiter workflow")
                    .map(snapshot -> snapshot.tasks().stream()
                            .anyMatch(task -> task.name().equals("waiter")
                                    && task.status() == TaskExecutionTaskStatus.WAITING))
                    .orElse(false));

            TaskExecutionSnapshot waiting = find(registry, "waiter workflow").orElseThrow();
            assertEquals(TaskExecutionStatus.WAITING, waiting.status());
            assertEquals(1, waiting.tasks().size());
            assertEquals(TaskExecutionTaskStatus.WAITING, waiting.tasks().get(0).status());
            assertTrue(registry.requestCancellation(waiting.id()));

            awaitCondition(() -> find(registry, "waiter workflow")
                    .map(snapshot -> snapshot.status() == TaskExecutionStatus.CANCELLED
                            && snapshot.tasks().stream().anyMatch(task ->
                            task.name().equals("waiter")
                                    && task.status() == TaskExecutionTaskStatus.CANCELLED))
                    .orElse(false));
            TaskExecutionSnapshot cancelled = find(registry, "waiter workflow").orElseThrow();
            assertEquals(TaskExecutionStatus.CANCELLED, cancelled.status());
            assertFalse(waiterBodyStarted.get());
            awaitCondition(cancellationVisibleToLegacyListener::get);
            assertTrue(cancelled.tasks().get(0).logs().stream()
                    .anyMatch(log -> log.event().equals("waiting")));
            assertTrue(cancelled.tasks().get(0).logs().stream()
                    .anyMatch(log -> log.event().equals("cancelled")));
        } finally {
            releaseHolder.countDown();
            assertTrue(holderResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            if (waiterResult != null) {
                assertFalse(waiterResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
        }
    }

    /// Waits for an asynchronous registry transition without blocking the executor worker indefinitely.
    ///
    /// @param condition condition that must become true
    /// @throws InterruptedException when the current test thread is interrupted
    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for task registry transition");
            }
            Thread.sleep(10L);
        }
    }

    /// Finds one uniquely titled execution in an isolated registry.
    ///
    /// @param registry isolated registry
    /// @param title unique top-level title
    /// @return matching execution, or empty while it has not been registered
    private static java.util.Optional<TaskExecutionSnapshot> find(
            TaskExecutionRegistry registry,
            String title) {
        return registry.snapshots().stream()
                .filter(snapshot -> snapshot.title().equals(title))
                .findFirst();
    }

    /// Supplies a boolean polling contract for asynchronous test conditions.
    @FunctionalInterface
    private interface BooleanSupplier {
        /// Evaluates the current condition.
        ///
        /// @return whether the condition holds
        boolean getAsBoolean();
    }
}
