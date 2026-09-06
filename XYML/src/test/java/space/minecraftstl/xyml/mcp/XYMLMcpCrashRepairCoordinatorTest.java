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
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.function.ExceptionalRunnable;
import space.minecraftstl.xyml.util.platform.Bits;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies source-bound planning and one-time execution of XYAT repair actions.
@NotNullByDefault
final class XYMLMcpCrashRepairCoordinatorTest {
    /// Minimal verified Fabric missing-dependency failure.
    private static final String FABRIC_MISSING_DEPENDENCY_LOG =
            "net.fabricmc.loader.discovery.ModResolutionException: Could not find required mod: "
                    + "pca requires {fabric-api @ [>=0.39.2]}";

    /// Maximum asynchronous operation wait.
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(5);

    /// Confirms a launcher-owned dependency diagnosis can be planned and executed exactly once.
    @Test
    void plansAndExecutesLauncherOwnedSearchOnce() throws Exception {
        AtomicInteger validations = new AtomicInteger();
        AtomicInteger taskCreations = new AtomicInteger();
        try (XYMLMcpCrashRepairCoordinator coordinator = new XYMLMcpCrashRepairCoordinator()) {
            Map<String, Object> analysis = coordinator.analyze(
                    "demo",
                    XYMLMcpCrashRepairCoordinator.AnalysisSource.LAUNCHER_LATEST_LOG,
                    "sha256:current",
                    missingDependencyInput(ignoredIds -> {
                        taskCreations.incrementAndGet();
                        return Task.completed(null);
                    }),
                    () -> validationTask(validations::incrementAndGet));
            Map<String, Object> diagnosis = firstDiagnosis(analysis);
            Map<String, Object> solution = solution(diagnosis);

            assertEquals("FABRIC_MISSING_DEPENDENCY", diagnosis.get("result_id"));
            assertEquals("OPEN_MOD_SEARCH", solution.get("action_type"));
            assertEquals(List.of("fabric-api"), solution.get("dependency_ids"));
            assertEquals(true, solution.get("mcp_executable"));
            assertEquals(0, taskCreations.get());

            Map<String, Object> plan = coordinator.plan(
                    String.valueOf(analysis.get("analysis_id")),
                    String.valueOf(solution.get("solution_id")));
            assertEquals(true, plan.get("planned"));
            assertEquals(true, plan.get("executable"));
            assertEquals(true, plan.get("single_use"));
            assertEquals(0, taskCreations.get());

            Map<String, Object> operation = coordinator.execute(String.valueOf(plan.get("plan_id")));
            assertEquals("SUCCEEDED", awaitTerminal(
                    coordinator,
                    String.valueOf(operation.get("operation_id"))).get("status"));
            assertEquals(1, validations.get());
            assertEquals(1, taskCreations.get());
            assertThrows(IllegalStateException.class,
                    () -> coordinator.execute(String.valueOf(plan.get("plan_id"))));
        }
    }

    /// Confirms caller-supplied logs remain analysis-only even when an input happens to carry a task boundary.
    @Test
    void blocksRepairPlanningForProvidedLogText() {
        AtomicInteger taskCreations = new AtomicInteger();
        try (XYMLMcpCrashRepairCoordinator coordinator = new XYMLMcpCrashRepairCoordinator()) {
            Map<String, Object> analysis = coordinator.analyze(
                    "demo",
                    XYMLMcpCrashRepairCoordinator.AnalysisSource.PROVIDED_LOG,
                    "sha256:external",
                    missingDependencyInput(ignoredIds -> {
                        taskCreations.incrementAndGet();
                        return Task.completed(null);
                    }),
                    null);
            Map<String, Object> solution = solution(firstDiagnosis(analysis));
            Map<String, Object> plan = coordinator.plan(
                    String.valueOf(analysis.get("analysis_id")),
                    String.valueOf(solution.get("solution_id")));

            assertEquals(false, solution.get("mcp_executable"));
            assertEquals(XYMLMcpCrashRepairCoordinator.EXTERNAL_LOG_NOT_EXECUTABLE,
                    solution.get("blocked_reason"));
            assertEquals(false, plan.get("planned"));
            assertEquals(false, plan.get("executable"));
            assertEquals(XYMLMcpCrashRepairCoordinator.EXTERNAL_LOG_NOT_EXECUTABLE,
                    plan.get("blocked_reason"));
            assertFalse(plan.containsKey("plan_id"));
            assertEquals(0, taskCreations.get());
        }
    }

    /// Confirms a source validation failure consumes the plan without starting its repair task.
    @Test
    void consumesPlanWhenLatestLogChanged() throws Exception {
        AtomicInteger taskCreations = new AtomicInteger();
        try (XYMLMcpCrashRepairCoordinator coordinator = new XYMLMcpCrashRepairCoordinator()) {
            Map<String, Object> analysis = coordinator.analyze(
                    "demo",
                    XYMLMcpCrashRepairCoordinator.AnalysisSource.LAUNCHER_LATEST_LOG,
                    "sha256:old",
                    missingDependencyInput(ignoredIds -> {
                        taskCreations.incrementAndGet();
                        return Task.completed(null);
                    }),
                    () -> validationTask(() -> {
                        throw new IllegalStateException(
                                "Crash analysis source could not be revalidated",
                                new IOException("latest log changed"));
                    }));
            Map<String, Object> solution = solution(firstDiagnosis(analysis));
            Map<String, Object> plan = coordinator.plan(
                    String.valueOf(analysis.get("analysis_id")),
                    String.valueOf(solution.get("solution_id")));
            String planId = String.valueOf(plan.get("plan_id"));

            Map<String, Object> operation = coordinator.execute(planId);
            Map<String, Object> terminal = awaitTerminal(
                    coordinator,
                    String.valueOf(operation.get("operation_id")));
            assertEquals("FAILED", terminal.get("status"));
            assertEquals(IllegalStateException.class.getName(), terminal.get("failure_type"));
            assertEquals("Crash analysis source could not be revalidated", terminal.get("failure_message"));
            assertEquals(0, taskCreations.get());
            assertThrows(IllegalStateException.class, () -> coordinator.execute(planId));
        }
    }

    /// Confirms non-destructive Java selection can be planned and executed without per-use confirmation.
    @Test
    void plansAndExecutesNonDestructiveJavaRepair() throws Exception {
        AtomicInteger validations = new AtomicInteger();
        AtomicInteger taskCreations = new AtomicInteger();
        String log = "java.lang.UnsupportedClassVersionError: example.Main has been compiled by a more recent "
                + "version of the Java Runtime (class file version 61.0), this version of the Java Runtime only "
                + "recognizes class file versions up to 52.0";
        LogAnalyzable input = baseInput(log, 17, 8)
                .withJavaRuntimeRepair(() -> {
                    taskCreations.incrementAndGet();
                    return Task.completed(null);
                });
        try (XYMLMcpCrashRepairCoordinator coordinator = new XYMLMcpCrashRepairCoordinator()) {
            Map<String, Object> analysis = coordinator.analyze(
                    "demo",
                    XYMLMcpCrashRepairCoordinator.AnalysisSource.LAUNCHER_LATEST_LOG,
                    "sha256:java",
                    input,
                    () -> validationTask(validations::incrementAndGet));
            Map<String, Object> solution = solution(firstDiagnosis(analysis));
            Map<String, Object> plan = coordinator.plan(
                    String.valueOf(analysis.get("analysis_id")),
                    String.valueOf(solution.get("solution_id")));

            assertEquals("REPLACE_JAVA_RUNTIME", solution.get("action_type"));
            assertEquals("NOT_REQUIRED", solution.get("confirmation_requirement"));
            assertEquals(true, solution.get("mcp_executable"));
            assertEquals(
                    List.of("OPEN_MOD_SEARCH", "REPLACE_JAVA_RUNTIME"),
                    repairExecutionPolicy(analysis).get("supported_action_types"));
            assertEquals(true, plan.get("planned"));
            assertEquals(true, plan.get("executable"));
            assertEquals(true, plan.get("single_use"));
            assertEquals(0, taskCreations.get());

            Map<String, Object> operation = coordinator.execute(String.valueOf(plan.get("plan_id")));
            assertEquals(false, operation.get("cancellable"));
            assertEquals("SUCCEEDED", awaitTerminal(
                    coordinator,
                    String.valueOf(operation.get("operation_id"))).get("status"));
            assertEquals(1, validations.get());
            assertEquals(1, taskCreations.get());
            assertThrows(IllegalStateException.class,
                    () -> coordinator.execute(String.valueOf(plan.get("plan_id"))));
        }
    }

    /// Starts source validation asynchronously instead of blocking the MCP execute call.
    ///
    /// @throws Exception when bounded synchronization or operation completion fails
    @Test
    void executesSourceValidationInsideTheAsynchronousOperation() throws Exception {
        CountDownLatch validationEntered = new CountDownLatch(1);
        CountDownLatch releaseValidation = new CountDownLatch(1);
        AtomicInteger taskCreations = new AtomicInteger();
        try (XYMLMcpCrashRepairCoordinator coordinator = new XYMLMcpCrashRepairCoordinator()) {
            Map<String, Object> analysis = coordinator.analyze(
                    "demo",
                    XYMLMcpCrashRepairCoordinator.AnalysisSource.LAUNCHER_LATEST_LOG,
                    "sha256:current",
                    missingDependencyInput(ignoredIds -> {
                        taskCreations.incrementAndGet();
                        return Task.completed(null);
                    }),
                    () -> validationTask(() -> {
                        validationEntered.countDown();
                        if (!releaseValidation.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("Timed out while holding source validation");
                        }
                    }));
            Map<String, Object> solution = solution(firstDiagnosis(analysis));
            Map<String, Object> plan = coordinator.plan(
                    String.valueOf(analysis.get("analysis_id")),
                    String.valueOf(solution.get("solution_id")));

            Map<String, Object> operation = assertTimeoutPreemptively(
                    Duration.ofSeconds(1),
                    () -> coordinator.execute(String.valueOf(plan.get("plan_id"))));
            assertTrue(validationEntered.await(5, TimeUnit.SECONDS));
            assertEquals(0, taskCreations.get());

            releaseValidation.countDown();
            assertEquals("SUCCEEDED", awaitTerminal(
                    coordinator,
                    String.valueOf(operation.get("operation_id"))).get("status"));
            assertEquals(1, taskCreations.get());
        } finally {
            releaseValidation.countDown();
        }
    }

    /// Cancels a source validator waiting on a conflicting resource without creating or running the repair task.
    ///
    /// @throws Exception when bounded synchronization or operation completion fails
    @Test
    void cancelsSourceValidationWhileWaitingForItsResource() throws Exception {
        TaskResource resource = TaskResource.gameInstance(Path.of("C:/Games/cancelled-validation"));
        CountDownLatch holderEntered = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        AtomicInteger validations = new AtomicInteger();
        AtomicInteger taskCreations = new AtomicInteger();
        Task<?> holder = validationTask(resource, () -> {
            holderEntered.countDown();
            if (!releaseHolder.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out while holding validation resource");
            }
        });
        TaskExecutor holderExecutor = holder.executor();
        holderExecutor.start();
        try (XYMLMcpCrashRepairCoordinator coordinator = new XYMLMcpCrashRepairCoordinator()) {
            assertTrue(holderEntered.await(5, TimeUnit.SECONDS));
            Map<String, Object> analysis = coordinator.analyze(
                    "demo",
                    XYMLMcpCrashRepairCoordinator.AnalysisSource.LAUNCHER_LATEST_LOG,
                    "sha256:current",
                    missingDependencyInput(ignoredIds -> {
                        taskCreations.incrementAndGet();
                        return Task.completed(null);
                    }),
                    () -> validationTask(resource, validations::incrementAndGet));
            Map<String, Object> solution = solution(firstDiagnosis(analysis));
            Map<String, Object> plan = coordinator.plan(
                    String.valueOf(analysis.get("analysis_id")),
                    String.valueOf(solution.get("solution_id")));
            Map<String, Object> operation = coordinator.execute(String.valueOf(plan.get("plan_id")));
            String operationId = String.valueOf(operation.get("operation_id"));

            Map<String, Object> cancellation = coordinator.cancel(operationId);
            assertEquals(true, cancellation.get("cancellation_accepted"));
            assertEquals("CANCELLED", awaitTerminal(coordinator, operationId).get("status"));
            assertEquals(0, validations.get());
            assertEquals(0, taskCreations.get());
        } finally {
            releaseHolder.countDown();
            awaitTaskTerminal(holder);
        }
    }

    /// Confirms plans expire independently of their longer-lived analysis.
    @Test
    void expiresRepairPlans() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T00:00:00Z"));
        try (XYMLMcpCrashRepairCoordinator coordinator = new XYMLMcpCrashRepairCoordinator(
                clock,
                Duration.ofMinutes(10),
                Duration.ofMinutes(1),
                2,
                2,
                new McpTaskOperationRegistry(clock, Duration.ofMinutes(10), 2))) {
            Map<String, Object> analysis = coordinator.analyze(
                    "demo",
                    XYMLMcpCrashRepairCoordinator.AnalysisSource.LAUNCHER_LATEST_LOG,
                    "sha256:current",
                    missingDependencyInput(ignoredIds -> Task.completed(null)),
                    () -> validationTask(() -> {
                    }));
            Map<String, Object> solution = solution(firstDiagnosis(analysis));
            Map<String, Object> plan = coordinator.plan(
                    String.valueOf(analysis.get("analysis_id")),
                    String.valueOf(solution.get("solution_id")));

            clock.advance(Duration.ofMinutes(2));
            assertThrows(IllegalArgumentException.class,
                    () -> coordinator.execute(String.valueOf(plan.get("plan_id"))));
        }
    }

    /// Creates a Fabric input with a fresh missing-dependency task factory.
    private static LogAnalyzable missingDependencyInput(
            LogAnalyzable.MissingDependencySearch search) {
        return baseInput(FABRIC_MISSING_DEPENDENCY_LOG, 17, 17)
                .withMissingDependencySearch(search);
    }

    /// Creates a contextual Windows analysis input.
    private static LogAnalyzable baseInput(String log, int requiredJava, int currentJava) {
        return new LogAnalyzable(
                "1.20.1",
                "net.minecraft.client.main.Main",
                ProcessListener.ExitType.APPLICATION_ERROR,
                OperatingSystem.WINDOWS,
                936,
                Path.of("C:/Games/demo"),
                Path.of("C:/Java/bin/java.exe"),
                requiredJava,
                currentJava,
                Bits.BIT_64,
                4096,
                log.lines().toList());
    }

    /// Creates one concrete-resource source-validation task.
    ///
    /// @param action validation action
    /// @return fresh stopped validation task
    private static Task<?> validationTask(ExceptionalRunnable<?> action) {
        return validationTask(TaskResource.gameInstance(Path.of("C:/Games/validation-demo")), action);
    }

    /// Creates one source-validation task for the supplied resource.
    ///
    /// @param resource concrete resource occupied by validation
    /// @param action validation action
    /// @return fresh stopped validation task
    private static Task<?> validationTask(TaskResource resource, ExceptionalRunnable<?> action) {
        return Task.runAsync("Validate crash source", Runnable::run, action).setResources(resource);
    }

    /// Extracts the first structured diagnosis.
    @SuppressWarnings("unchecked")
    private static @Unmodifiable Map<String, Object> firstDiagnosis(Map<String, Object> analysis) {
        List<Map<String, Object>> diagnoses = (List<Map<String, Object>>) analysis.get("diagnoses");
        assertEquals(1, diagnoses.size());
        return diagnoses.get(0);
    }

    /// Extracts the structured solution from one diagnosis.
    @SuppressWarnings("unchecked")
    private static @Unmodifiable Map<String, Object> solution(Map<String, Object> diagnosis) {
        return (Map<String, Object>) diagnosis.get("solution");
    }

    /// Extracts the advertised MCP repair execution policy.
    @SuppressWarnings("unchecked")
    private static @Unmodifiable Map<String, Object> repairExecutionPolicy(Map<String, Object> analysis) {
        return (Map<String, Object>) analysis.get("repair_execution_policy");
    }

    /// Polls one repair operation until its terminal state is visible.
    private static @Unmodifiable Map<String, Object> awaitTerminal(
            XYMLMcpCrashRepairCoordinator coordinator,
            String operationId) throws InterruptedException {
        Instant deadline = Instant.now().plus(OPERATION_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            Map<String, Object> status = coordinator.status(operationId);
            if (switch (String.valueOf(status.get("status"))) {
                    case "SUCCEEDED", "FAILED", "CANCELLED" -> true;
                    default -> false;
                }) {
                return status;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Crash repair operation did not finish before timeout");
    }

    /// Waits for an independently started task to reach a terminal state.
    ///
    /// @param task task whose terminal state is required
    /// @throws InterruptedException when polling is interrupted
    private static void awaitTaskTerminal(Task<?> task) throws InterruptedException {
        Instant deadline = Instant.now().plus(OPERATION_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (task.getState() == Task.TaskState.SUCCEEDED || task.getState() == Task.TaskState.FAILED) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Task did not finish before timeout");
    }

    /// Mutable UTC clock used to cross plan-expiry boundaries deterministically.
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

        /// Returns this UTC-only clock for the supported zone.
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
