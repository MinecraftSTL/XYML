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
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies source-bound planning, independent execution, and retryable XYAT repair actions.
@NotNullByDefault
final class XYMLMcpCrashRepairCoordinatorTest {
    /// Minimal verified Fabric missing-dependency failure.
    private static final String FABRIC_MISSING_DEPENDENCY_LOG =
            "net.fabricmc.loader.discovery.ModResolutionException: Could not find required mod: "
                    + "pca requires {fabric-api @ [>=0.39.2]}";

    /// Maximum asynchronous operation wait.
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(5);

    /// Confirms a launcher-owned dependency diagnosis can be planned and executed once successfully.
    @Test
    void plansAndExecutesLauncherOwnedSearch() throws Exception {
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
            assertEquals(false, plan.get("single_use"));
            assertEquals(true, plan.get("retry_after_failure"));
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

    /// Publishes immutable per-cause evidence, superseded legacy matches, and the initial repair state.
    @Test
    @SuppressWarnings("unchecked")
    void publishesImmutableCauseEvidenceSnapshot() {
        String log = "Native memory allocation (mmap) failed to commit 1048576 bytes\n"
                + "java.lang.OutOfMemoryError: Java heap space";
        try (XYMLMcpCrashRepairCoordinator coordinator = new XYMLMcpCrashRepairCoordinator()) {
            Map<String, Object> analysis = coordinator.analyze(
                    "demo",
                    XYMLMcpCrashRepairCoordinator.AnalysisSource.LAUNCHER_LATEST_LOG,
                    "sha256:evidence-snapshot",
                    baseInput(log, 17, 17),
                    () -> validationTask(() -> {
                    }));
            Map<String, Object> diagnosis = firstDiagnosis(analysis);
            Map<String, Object> cause = (Map<String, Object>) diagnosis.get("cause_snapshot");
            List<String> evidenceSources = (List<String>) diagnosis.get("evidence_sources");
            List<Map<String, Object>> suppressed = (List<Map<String, Object>>) diagnosis.get("suppressed_evidence");

            assertEquals("VIRTUAL_MEMORY", diagnosis.get("result_id"));
            assertEquals("BLOCKED", diagnosis.get("repair_state"));
            assertEquals(List.of("launcher_latest_log"), evidenceSources);
            assertEquals(evidenceSources, cause.get("evidence_sources"));
            assertEquals("BLOCKED", cause.get("repair_state"));
            assertEquals(
                    List.of("MEMORY_EXCEEDED", "OUT_OF_MEMORY"),
                    suppressed.stream().map(entry -> entry.get("result_id")).toList());
            assertEquals("VIRTUAL_MEMORY", suppressed.get(0).get("suppressed_by"));
            assertTrue(String.valueOf(suppressed.get(0).get("matched_text"))
                    .contains("Native memory allocation"));

            assertThrows(UnsupportedOperationException.class, () -> evidenceSources.add("unexpected"));
            assertThrows(UnsupportedOperationException.class, () -> suppressed.add(Map.of()));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> suppressed.get(0).put("result_id", "unexpected"));
            assertThrows(UnsupportedOperationException.class, () -> cause.put("repair_state", "unexpected"));
            assertThrows(UnsupportedOperationException.class, () -> diagnosis.put("repair_state", "unexpected"));
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

    /// Confirms a source validation failure leaves the plan retryable without starting its repair task.
    @Test
    void keepsPlanRetryableWhenLatestLogChanged() throws Exception {
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
            assertEquals("FAILED_RETRYABLE", terminal.get("plan_state"));
            assertEquals(true, terminal.get("retryable"));
            assertEquals(0, taskCreations.get());
            assertThrows(IllegalStateException.class, () -> coordinator.execute(planId));

            Map<String, Object> retry = coordinator.retry(planId);
            assertTrue(!String.valueOf(retry.get("operation_id")).equals(
                    String.valueOf(operation.get("operation_id"))));
            Map<String, Object> retryTerminal = awaitTerminal(
                    coordinator,
                    String.valueOf(retry.get("operation_id")));
            assertEquals("FAILED", retryTerminal.get("status"));
            assertEquals("FAILED_RETRYABLE", retryTerminal.get("plan_state"));
            assertEquals(0, taskCreations.get());
        }
    }

    /// Confirms a task-factory failure leaves the plan retryable and the retry creates a fresh task instance.
    @Test
    void retriesAfterTaskFactoryFailureWithFreshTask() throws Exception {
        AtomicInteger taskCreations = new AtomicInteger();
        LogAnalyzable input = missingDependencyInput(ignoredIds -> {
            if (taskCreations.incrementAndGet() == 1) {
                throw new IllegalStateException("first repair task unavailable");
            }
            return Task.completed(null);
        });
        try (XYMLMcpCrashRepairCoordinator coordinator = new XYMLMcpCrashRepairCoordinator()) {
            Map<String, Object> analysis = coordinator.analyze(
                    "demo",
                    XYMLMcpCrashRepairCoordinator.AnalysisSource.LAUNCHER_LATEST_LOG,
                    "sha256:factory-retry",
                    input,
                    () -> validationTask(() -> {
                    }));
            Map<String, Object> solution = solution(firstDiagnosis(analysis));
            Map<String, Object> plan = coordinator.plan(
                    String.valueOf(analysis.get("analysis_id")),
                    String.valueOf(solution.get("solution_id")));
            String planId = String.valueOf(plan.get("plan_id"));

            Map<String, Object> firstOperation = coordinator.execute(planId);
            Map<String, Object> firstTerminal = awaitTerminal(
                    coordinator,
                    String.valueOf(firstOperation.get("operation_id")));
            assertEquals("FAILED", firstTerminal.get("status"));
            assertEquals("FAILED_RETRYABLE", firstTerminal.get("plan_state"));
            assertEquals(1, taskCreations.get());
            @SuppressWarnings("unchecked")
            List<String> completedBeforeRetry = (List<String>) firstTerminal.get("completed_steps");
            assertFalse(completedBeforeRetry.isEmpty());

            Map<String, Object> retryOperation = coordinator.retry(planId);
            assertTrue(!String.valueOf(firstOperation.get("operation_id")).equals(
                    String.valueOf(retryOperation.get("operation_id"))));
            assertEquals(completedBeforeRetry, retryOperation.get("retained_completed_steps"));
            Map<String, Object> retryTerminal = awaitTerminal(
                    coordinator,
                    String.valueOf(retryOperation.get("operation_id")));
            assertEquals("SUCCEEDED", retryTerminal.get("status"));
            assertEquals("SUCCEEDED", retryTerminal.get("plan_state"));
            assertEquals(2, taskCreations.get());

            Map<String, Object> staleOperation = coordinator.status(
                    String.valueOf(firstOperation.get("operation_id")));
            assertEquals(firstOperation.get("operation_id"), staleOperation.get("operation_id"));
            assertEquals(retryOperation.get("operation_id"), staleOperation.get("current_operation_id"));
            assertEquals("FAILED", staleOperation.get("status"));
            assertEquals("SUCCEEDED", staleOperation.get("plan_state"));
        }
    }

    /// Confirms a source-validator factory failure is retryable and the retry builds a fresh validation task.
    @Test
    void retriesAfterSourceValidatorFactoryFailureWithFreshTask() throws Exception {
        AtomicInteger validationTaskCreations = new AtomicInteger();
        AtomicInteger repairTaskCreations = new AtomicInteger();
        try (XYMLMcpCrashRepairCoordinator coordinator = new XYMLMcpCrashRepairCoordinator()) {
            Map<String, Object> analysis = coordinator.analyze(
                    "demo",
                    XYMLMcpCrashRepairCoordinator.AnalysisSource.LAUNCHER_LATEST_LOG,
                    "sha256:validator-factory-retry",
                    missingDependencyInput(ignoredIds -> {
                        repairTaskCreations.incrementAndGet();
                        return Task.completed(null);
                    }),
                    () -> {
                        if (validationTaskCreations.incrementAndGet() == 1) {
                            throw new IllegalStateException("first source validation task unavailable");
                        }
                        return validationTask(() -> {
                        });
                    });
            Map<String, Object> solution = solution(firstDiagnosis(analysis));
            Map<String, Object> plan = coordinator.plan(
                    String.valueOf(analysis.get("analysis_id")),
                    String.valueOf(solution.get("solution_id")));
            String planId = String.valueOf(plan.get("plan_id"));

            Map<String, Object> firstOperation = coordinator.execute(planId);
            Map<String, Object> firstTerminal = awaitTerminal(
                    coordinator,
                    String.valueOf(firstOperation.get("operation_id")));
            assertEquals("FAILED", firstTerminal.get("status"));
            assertEquals("FAILED_RETRYABLE", firstTerminal.get("plan_state"));
            assertEquals(1, validationTaskCreations.get());
            assertEquals(0, repairTaskCreations.get());

            Map<String, Object> retryOperation = coordinator.retry(planId);
            assertNotEquals(firstOperation.get("operation_id"), retryOperation.get("operation_id"));
            Map<String, Object> retryTerminal = awaitTerminal(
                    coordinator,
                    String.valueOf(retryOperation.get("operation_id")));
            assertEquals("SUCCEEDED", retryTerminal.get("status"));
            assertEquals("SUCCEEDED", retryTerminal.get("plan_state"));
            assertEquals(2, validationTaskCreations.get());
            assertEquals(1, repairTaskCreations.get());
        }
    }

    /// Confirms one retry reservation rejects a concurrent retry until the fresh operation is registered.
    @Test
    void serializesConcurrentRetriesWhileCreatingTheFreshOperation() throws Exception {
        CountDownLatch retryFactoryEntered = new CountDownLatch(1);
        CountDownLatch releaseRetryFactory = new CountDownLatch(1);
        AtomicInteger validationTaskCreations = new AtomicInteger();
        AtomicInteger repairTaskCreations = new AtomicInteger();
        AtomicReference<@Nullable Map<String, Object>> retryOperation = new AtomicReference<>();
        AtomicReference<@Nullable Throwable> retryFailure = new AtomicReference<>();
        LogAnalyzable input = missingDependencyInput(ignoredIds -> {
            repairTaskCreations.incrementAndGet();
            return Task.completed(null);
        });
        try (XYMLMcpCrashRepairCoordinator coordinator = new XYMLMcpCrashRepairCoordinator()) {
            Map<String, Object> analysis = coordinator.analyze(
                    "demo",
                    XYMLMcpCrashRepairCoordinator.AnalysisSource.LAUNCHER_LATEST_LOG,
                    "sha256:concurrent-retry",
                    input,
                    () -> {
                        int creation = validationTaskCreations.incrementAndGet();
                        if (creation == 1) {
                            throw new IllegalStateException("initial validation task unavailable");
                        }
                        retryFactoryEntered.countDown();
                        try {
                            if (!releaseRetryFactory.await(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                                throw new IllegalStateException("Timed out while holding the retry task factory");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Retry task factory was interrupted", interrupted);
                        }
                        return validationTask(() -> {
                        });
                    });
            Map<String, Object> solution = solution(firstDiagnosis(analysis));
            Map<String, Object> plan = coordinator.plan(
                    String.valueOf(analysis.get("analysis_id")),
                    String.valueOf(solution.get("solution_id")));
            String planId = String.valueOf(plan.get("plan_id"));

            Map<String, Object> failedOperation = coordinator.execute(planId);
            Map<String, Object> failedTerminal = awaitTerminal(
                    coordinator,
                    String.valueOf(failedOperation.get("operation_id")));
            assertEquals("FAILED", failedTerminal.get("status"));
            assertEquals("FAILED_RETRYABLE", failedTerminal.get("plan_state"));

            Thread retryThread = new Thread(() -> {
                try {
                    retryOperation.set(coordinator.retry(planId));
                } catch (Throwable failure) {
                    retryFailure.set(failure);
                }
            }, "mcp-crash-retry-reservation-test");
            retryThread.start();
            try {
                assertTrue(retryFactoryEntered.await(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                Map<String, Object> staleStatus = coordinator.status(
                        String.valueOf(failedOperation.get("operation_id")));
                assertEquals("FAILED", staleStatus.get("status"));
                assertEquals("RUNNING", staleStatus.get("plan_state"));
                IllegalStateException competingFailure = assertThrows(
                        IllegalStateException.class,
                        () -> coordinator.retry(planId));
                assertTrue(competingFailure.getMessage().contains("retry is already in progress"));
            } finally {
                releaseRetryFactory.countDown();
            }
            retryThread.join(OPERATION_TIMEOUT.toMillis());

            assertFalse(retryThread.isAlive());
            assertNull(retryFailure.get());
            Map<String, Object> createdOperation = java.util.Objects.requireNonNull(retryOperation.get());
            assertNotEquals(failedOperation.get("operation_id"), createdOperation.get("operation_id"));
            Map<String, Object> succeededTerminal = awaitTerminal(
                    coordinator,
                    String.valueOf(createdOperation.get("operation_id")));
            assertEquals("SUCCEEDED", succeededTerminal.get("status"));
            assertEquals("SUCCEEDED", succeededTerminal.get("plan_state"));
            assertEquals(2, validationTaskCreations.get());
            assertEquals(1, repairTaskCreations.get());
        } finally {
            releaseRetryFactory.countDown();
        }
    }

    /// Confirms successful residual cleanup restores the original operation instead of replaying its repair task.
    @Test
    void doesNotReplaySuccessfulOperationAfterResidualCleanup() throws Exception {
        AtomicInteger executorCreations = new AtomicInteger();
        AtomicInteger validationTaskCreations = new AtomicInteger();
        McpTaskOperationRegistry operations = new McpTaskOperationRegistry(
                Clock.systemUTC(),
                Duration.ofMinutes(10),
                8,
                task -> {
                    executorCreations.incrementAndGet();
                    return new ResidualCleanupExecutor(task);
                });
        try (XYMLMcpCrashRepairCoordinator coordinator = new XYMLMcpCrashRepairCoordinator(
                Clock.systemUTC(),
                Duration.ofMinutes(10),
                Duration.ofMinutes(10),
                8,
                8,
                operations)) {
            Map<String, Object> analysis = coordinator.analyze(
                    "demo",
                    XYMLMcpCrashRepairCoordinator.AnalysisSource.LAUNCHER_LATEST_LOG,
                    "sha256:residual-success",
                    missingDependencyInput(ignoredIds -> Task.completed(null)),
                    () -> {
                        validationTaskCreations.incrementAndGet();
                        return Task.completed(null);
                    });
            Map<String, Object> solution = solution(firstDiagnosis(analysis));
            Map<String, Object> plan = coordinator.plan(
                    String.valueOf(analysis.get("analysis_id")),
                    String.valueOf(solution.get("solution_id")));
            String planId = String.valueOf(plan.get("plan_id"));

            Map<String, Object> firstOperation = coordinator.execute(planId);
            String operationId = String.valueOf(firstOperation.get("operation_id"));
            Map<String, Object> blocked = awaitTerminal(coordinator, operationId);
            assertEquals("BLOCKED_RESIDUAL", blocked.get("status"));
            assertEquals("BLOCKED_RESIDUAL", blocked.get("plan_state"));
            assertEquals(List.of("synthetic residual"), blocked.get("residual_resources"));
            assertEquals(1, executorCreations.get());
            assertEquals(1, validationTaskCreations.get());

            Map<String, Object> cleaned = coordinator.retry(planId);
            assertEquals(operationId, cleaned.get("operation_id"));
            assertEquals("SUCCEEDED", cleaned.get("status"));
            assertEquals("SUCCEEDED", cleaned.get("plan_state"));
            assertEquals(List.of(), cleaned.get("residual_resources"));
            assertEquals(List.of(), cleaned.get("failed_steps"));
            assertEquals(1, executorCreations.get());
            assertEquals(1, validationTaskCreations.get());
            assertThrows(IllegalStateException.class, () -> coordinator.retry(planId));
        }
    }

    /// Confirms Java selection is exposed as one confirmed, executable repair solution.
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
            assertEquals("REQUIRED", solution.get("confirmation_requirement"));
            assertEquals(true, solution.get("mcp_executable"));
            assertEquals(
                    List.of("OPEN_MOD_SEARCH", "REPLACE_JAVA_RUNTIME"),
                    repairExecutionPolicy(analysis).get("supported_action_types"));
            assertEquals(true, plan.get("planned"));
            assertEquals(true, plan.get("executable"));
            assertEquals(false, plan.get("single_use"));
            assertEquals(true, plan.get("retry_after_failure"));
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

    /// Keeps a running plan and its operation link reachable when both retention clocks cross the plan deadline.
    ///
    /// @throws Exception when bounded task synchronization or completion fails
    @Test
    void retainsRunningPlanPastExpiry() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T00:00:00Z"));
        CountDownLatch validationEntered = new CountDownLatch(1);
        CountDownLatch releaseValidation = new CountDownLatch(1);
        try (XYMLMcpCrashRepairCoordinator coordinator = new XYMLMcpCrashRepairCoordinator(
                clock,
                Duration.ofMinutes(1),
                Duration.ofMinutes(1),
                2,
                2,
                new McpTaskOperationRegistry(clock, Duration.ofMinutes(10), 2))) {
            Map<String, Object> analysis = coordinator.analyze(
                    "demo",
                    XYMLMcpCrashRepairCoordinator.AnalysisSource.LAUNCHER_LATEST_LOG,
                    "sha256:running-expiry",
                    missingDependencyInput(ignoredIds -> Task.completed(null)),
                    () -> validationTask(() -> {
                        validationEntered.countDown();
                        if (!releaseValidation.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("Timed out while holding running expiry validation");
                        }
                    }));
            Map<String, Object> solution = solution(firstDiagnosis(analysis));
            Map<String, Object> plan = coordinator.plan(
                    String.valueOf(analysis.get("analysis_id")),
                    String.valueOf(solution.get("solution_id")));
            Map<String, Object> operation = coordinator.execute(String.valueOf(plan.get("plan_id")));
            String operationId = String.valueOf(operation.get("operation_id"));
            assertTrue(validationEntered.await(5, TimeUnit.SECONDS));

            clock.advance(Duration.ofMinutes(2));
            Map<String, Object> running = coordinator.status(operationId);
            assertEquals("RUNNING", running.get("status"));
            assertEquals("RUNNING", running.get("plan_state"));
            assertEquals(operationId, running.get("current_operation_id"));

            releaseValidation.countDown();
            assertEquals("SUCCEEDED", awaitTerminal(coordinator, operationId).get("status"));
        } finally {
            releaseValidation.countDown();
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
                    case "SUCCEEDED", "FAILED", "BLOCKED_RESIDUAL", "CANCELLED" -> true;
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

    /// Executor fixture that reports one residual lease before succeeding on its first cleanup retry.
    @NotNullByDefault
    private static final class ResidualCleanupExecutor extends TaskExecutor {
        /// Whether the synthetic residual is still present.
        private volatile boolean residual = true;

        /// Creates a fixture rooted at the supplied task.
        private ResidualCleanupExecutor(Task<?> task) {
            super(task);
        }

        /// Publishes one successful task completion while retaining a synthetic residual resource.
        @Override
        public TaskExecutor start() {
            notifyTaskListeners(TaskListener::onStart);
            notifyTaskListeners(listener -> listener.onStop(true, this));
            return this;
        }

        /// Runs the same deterministic completion path synchronously.
        @Override
        public boolean test() {
            start();
            return true;
        }

        /// This fixture has no active work to cancel.
        @Override
        public void cancel() {
            cancelled = true;
        }

        /// Returns the synthetic residual until cleanup succeeds.
        @Override
        public @Unmodifiable List<String> getResidualResources() {
            return residual ? List.of("synthetic residual") : List.of();
        }

        /// Clears the synthetic residual on the first retry.
        @Override
        public boolean retryResourceCleanup() {
            residual = false;
            return true;
        }
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
