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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.analyzer.RepairCheckpoint;
import space.minecraftstl.xyml.task.Task;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies bounded, observable, and cancellable MCP task execution.
@NotNullByDefault
public final class McpTaskOperationRegistryTest {
    /// Maximum time allowed for an asynchronous test task to reach a terminal state.
    private static final Duration TASK_TIMEOUT = Duration.ofSeconds(5);

    /// Confirms a successful task publishes a complete terminal snapshot.
    @Test
    public void publishesSuccessfulTerminalState() throws Exception {
        try (McpTaskOperationRegistry registry = new McpTaskOperationRegistry()) {
            Map<String, Object> started = registry.start(
                    "OPEN_MOD_SEARCH", true, () -> Task.runAsync(() -> {
                    }));
            Map<String, Object> finished = awaitTerminal(registry, operationId(started));

            assertEquals("SUCCEEDED", finished.get("status"));
            assertEquals("OPEN_MOD_SEARCH", finished.get("action_type"));
            assertFalse((boolean) finished.get("cancellable"));
            assertTrue(finished.containsKey("started_at"));
            assertTrue(finished.containsKey("finished_at"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> steps = (List<Map<String, Object>>) finished.get("steps");
            assertFalse(steps.isEmpty());
            assertEquals("SUCCEEDED", steps.get(0).get("status"));
        }
    }

    /// Confirms task-factory failures remain queryable without losing their public operation ID.
    @Test
    public void retainsTaskCreationFailure() {
        try (McpTaskOperationRegistry registry = new McpTaskOperationRegistry()) {
            Map<String, Object> failed = registry.start("OPEN_MOD_SEARCH", true, () -> {
                throw new IllegalStateException("runtime unavailable\nretry later");
            });

            assertEquals("FAILED", failed.get("status"));
            assertEquals("java.lang.IllegalStateException", failed.get("failure_type"));
            assertEquals("runtime unavailable retry later", failed.get("failure_message"));
            assertEquals(failed, registry.status(operationId(failed)));
        }
    }

    /// Confirms a retry checkpoint survives task creation failure and is returned to the caller.
    @Test
    public void retainsRetryCheckpointWhenFactoryFails() {
        RepairCheckpoint checkpoint = new RepairCheckpoint(
                List.of("validate source"),
                List.of("install dependency"),
                "install dependency");
        try (McpTaskOperationRegistry registry = new McpTaskOperationRegistry()) {
            Map<String, Object> failed = registry.startForOwner(
                    "OPEN_MOD_SEARCH",
                    true,
                    checkpoint,
                    () -> {
                        throw new IllegalStateException("factory unavailable");
                    });

            assertEquals(List.of("validate source"), failed.get("retained_completed_steps"));
            assertEquals(List.of("validate source"), failed.get("completed_steps"));
            assertEquals(List.of("OPEN_MOD_SEARCH"), failed.get("failed_steps"));
            assertEquals("OPEN_MOD_SEARCH", failed.get("resume_from_step"));
        }
    }

    /// Confirms asynchronous task failures are available without exposing a stack trace.
    @Test
    public void publishesTaskExecutionFailure() throws Exception {
        try (McpTaskOperationRegistry registry = new McpTaskOperationRegistry()) {
            Map<String, Object> started = registry.start(
                    "OPEN_MOD_SEARCH", true, () -> Task.runAsync("scan missing dependency", () -> {
                        throw new IOException("search failed");
                    }));
            Map<String, Object> failed = awaitTerminal(registry, operationId(started));

            assertEquals("FAILED", failed.get("status"));
            assertEquals("java.io.IOException", failed.get("failure_type"));
            assertEquals("search failed", failed.get("failure_message"));
            assertFalse(failed.containsKey("stack_trace"));
            assertEquals("scan missing dependency", failed.get("failed_step"));
            assertTrue(((List<?>) failed.get("failed_steps")).contains("scan missing dependency"));
        }
    }

    /// Confirms cooperative cancellation is accepted only for active operations and reaches a terminal state.
    @Test
    public void requestsCooperativeCancellation() throws Exception {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        try (McpTaskOperationRegistry registry = new McpTaskOperationRegistry()) {
            Map<String, Object> started = registry.start("OPEN_MOD_SEARCH", true, () -> Task.runAsync(() -> {
                taskStarted.countDown();
                assertTrue(releaseTask.await(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            }));
            assertTrue(taskStarted.await(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

            Map<String, Object> cancelling = registry.cancel(operationId(started));
            assertEquals(true, cancelling.get("cancellation_accepted"));
            assertEquals(true, cancelling.get("cancel_requested"));

            releaseTask.countDown();
            Map<String, Object> finished = awaitTerminal(registry, operationId(started));
            assertEquals("CANCELLED", finished.get("status"));
            assertEquals(true, finished.get("cancel_requested"));
            assertEquals(false, registry.cancel(operationId(started)).get("cancellation_accepted"));
        }
    }

    /// Confirms non-cancellable operations reject cancellation without mutating their state.
    @Test
    public void rejectsCancellationForProtectedOperation() throws Exception {
        CountDownLatch releaseTask = new CountDownLatch(1);
        try (McpTaskOperationRegistry registry = new McpTaskOperationRegistry()) {
            Map<String, Object> started = registry.start("REPLACE_JAVA_RUNTIME", false,
                    () -> Task.runAsync(() -> releaseTask.await(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)));

            Map<String, Object> result = registry.cancel(operationId(started));
            assertEquals(false, result.get("cancellation_accepted"));
            assertEquals(false, result.get("cancel_requested"));
            releaseTask.countDown();
            assertEquals("SUCCEEDED", awaitTerminal(registry, operationId(started)).get("status"));
        }
    }

    /// Confirms closing the registry while a task is being prepared prevents any later task side effect.
    @Test
    public void closeRejectsOperationBeingPrepared() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch taskExecuted = new CountDownLatch(1);
        AtomicReference<@Nullable Map<String, Object>> startResult = new AtomicReference<>();
        AtomicReference<@Nullable Throwable> startFailure = new AtomicReference<>();
        McpTaskOperationRegistry registry = new McpTaskOperationRegistry();
        Thread starter = new Thread(() -> {
            try {
                startResult.set(registry.start("OPEN_MOD_SEARCH", true, () -> {
                    factoryEntered.countDown();
                    try {
                        assertTrue(releaseFactory.await(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                    return Task.runAsync(taskExecuted::countDown);
                }));
            } catch (Throwable failure) {
                startFailure.set(failure);
            }
        }, "mcp-repair-start-test");

        starter.start();
        assertTrue(factoryEntered.await(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        registry.close();
        releaseFactory.countDown();
        starter.join(TASK_TIMEOUT.toMillis());

        assertFalse(starter.isAlive());
        assertNull(startFailure.get());
        Map<String, Object> cancelled = Objects.requireNonNull(startResult.get());
        assertEquals("CANCELLED", cancelled.get("status"));
        assertEquals(true, cancelled.get("cancel_requested"));
        assertFalse(taskExecuted.await(100L, TimeUnit.MILLISECONDS));
        registry.close();
    }

    /// Confirms terminal snapshots expire according to the configured monotonic policy boundary.
    @Test
    public void expiresTerminalSnapshots() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T00:00:00Z"));
        try (McpTaskOperationRegistry registry =
                     new McpTaskOperationRegistry(clock, Duration.ofMinutes(1), 2)) {
            Map<String, Object> started = registry.start(
                    "OPEN_MOD_SEARCH", true, () -> Task.runAsync(() -> {
                    }));
            String operationId = operationId(started);
            awaitTerminal(registry, operationId);

            clock.advance(Duration.ofMinutes(1));
            assertThrows(IllegalArgumentException.class, () -> registry.status(operationId));
        }
    }

    /// Polls one operation until it reaches a terminal state.
    private static @Unmodifiable Map<String, Object> awaitTerminal(
            McpTaskOperationRegistry registry,
            String operationId) throws InterruptedException {
        Instant deadline = Instant.now().plus(TASK_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            @Unmodifiable Map<String, Object> status = registry.status(operationId);
            if (switch (String.valueOf(status.get("status"))) {
                    case "SUCCEEDED", "FAILED", "CANCELLED" -> true;
                    default -> false;
                }) {
                return status;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Operation did not finish before timeout");
    }

    /// Reads a required operation identifier from a registry result.
    private static String operationId(Map<String, Object> result) {
        return String.valueOf(result.get("operation_id"));
    }

    /// Mutable UTC clock used to cross retention boundaries deterministically.
    @NotNullByDefault
    private static final class MutableClock extends Clock {
        /// Current synthetic instant.
        private Instant instant;

        /// Creates a clock at one fixed instant.
        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        /// Returns UTC as the fixed test zone.
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /// Returns an equivalent clock because this fixture is deliberately UTC-only.
        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported");
            }
            return this;
        }

        /// Returns the current synthetic instant.
        @Override
        public Instant instant() {
            return instant;
        }

        /// Advances the synthetic instant.
        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
