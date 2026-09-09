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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.java.JavaManager;
import space.minecraftstl.xyml.setting.GameDirectory;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.setting.GameSettingsPresets;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.PortablePath;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies source ownership, freshness, and fail-soft context collection at the MCP service boundary.
@Isolated
@NotNullByDefault
final class XYMLMcpServiceCrashAnalysisTest {
    /// Minimal verified Fabric missing-dependency failure.
    private static final String FABRIC_MISSING_DEPENDENCY_LOG =
            "net.fabricmc.loader.discovery.ModResolutionException: Could not find required mod: "
                    + "pca requires {fabric-api @ [>=0.39.2]}";

    /// Checks startup policy before creating any underlying repair task or executing its side effects.
    ///
    /// @throws Exception when the allowed task unexpectedly fails
    @Test
    void gatesRepairTaskCreationAtExecutionTime() throws Exception {
        AtomicInteger taskCreations = new AtomicInteger();
        AtomicInteger taskExecutions = new AtomicInteger();
        Supplier<Task<?>> taskFactory = () -> {
            taskCreations.incrementAndGet();
            return Task.runAsync(Runnable::run, taskExecutions::incrementAndGet);
        };

        Task<?> blockedTask = XYMLMcpService.guardRepairTask(() -> false, taskFactory);
        assertEquals(TaskResource.Kind.ORCHESTRATION, blockedTask.getResources().iterator().next().getKind());
        IllegalStateException blocked = assertThrows(IllegalStateException.class, blockedTask::run);
        assertTrue(blocked.getMessage().contains("startup agreements"));
        assertEquals(0, taskCreations.get());
        assertEquals(0, taskExecutions.get());

        XYMLMcpService.guardRepairTask(() -> true, taskFactory).run();
        assertEquals(1, taskCreations.get());
        assertEquals(1, taskExecutions.get());
    }

    /// Exercises real repository paths while replacing process-global settings only for this isolated test.
    ///
    /// @param repositoryRoot temporary game repository root
    /// @throws Exception when fixture setup, analysis, or settings restoration fails
    @Test
    void preservesReadOnlyAnalysisAndRevalidatesLauncherOwnedLogs(@TempDir Path repositoryRoot) throws Exception {
        Field launcherSettingsField = SettingsManager.class.getDeclaredField("launcherSettings");
        Field gameSettingsPresetsField = SettingsManager.class.getDeclaredField("gameSettingsPresets");
        launcherSettingsField.setAccessible(true);
        gameSettingsPresetsField.setAccessible(true);
        @Nullable Object previousLauncherSettings = launcherSettingsField.get(null);
        @Nullable Object previousGameSettingsPresets = gameSettingsPresetsField.get(null);

        LauncherSettings launcherSettings = new LauncherSettings();
        GameSettingsPresetID presetId = GameSettingsPresetID.generate();
        GameSettings.Preset preset = new GameSettings.Preset(presetId);
        GameSettingsPresets presets = new GameSettingsPresets();
        presets.setSavable(false);
        presets.getPresets().add(preset);
        launcherSettings.defaultGameSettingsPresetProperty().set(presetId);
        launcherSettingsField.set(null, launcherSettings);
        gameSettingsPresetsField.set(null, presets);
        try {
            XYMLGameRepository repository = repository(repositoryRoot);
            GameInstanceID validId = new GameInstanceID("1.20.1-fabric");
            GameInstanceID parentId = new GameInstanceID("parent-instance");
            GameInstanceID brokenId = new GameInstanceID("broken-child");
            writeManifest(repository, new GameInstanceManifest(validId)
                    .withMainClass("net.fabricmc.loader.impl.launch.knot.KnotClient"));
            writeManifest(repository, new GameInstanceManifest(parentId)
                    .withMainClass("net.minecraft.client.main.Main"));
            writeManifest(repository, new GameInstanceManifest(brokenId)
                    .withInheritsFrom(parentId));
            repository.refresh();

            AtomicInteger searchTaskCreations = new AtomicInteger();
            try (XYMLMcpService service = new XYMLMcpService(
                    repository,
                    ignoredRequest -> false,
                    ignoredIds -> Task.runAsync(Runnable::run, searchTaskCreations::incrementAndGet))) {
                Path latestLog = repository.getRunDirectory(validId).resolve("logs").resolve("latest.log");
                Files.createDirectories(latestLog.getParent());
                Files.writeString(latestLog, "persisted baseline without a crash rule", StandardCharsets.UTF_8);
                boolean javaDiscoveryReady = JavaManager.isInitialized();

                // Simulate output that was captured by the launch listener but has not yet been flushed to latest.log.
                Field launchStatesField = XYMLMcpService.class.getDeclaredField("launchStates");
                launchStatesField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Object, Object> launchStates = (Map<Object, Object>) launchStatesField.get(service);
                Class<?> launchKeyClass = Class.forName(
                        "space.minecraftstl.xyml.mcp.XYMLMcpService$LaunchKey");
                var launchKeyConstructor = launchKeyClass.getDeclaredConstructor(Path.class, GameInstanceID.class);
                launchKeyConstructor.setAccessible(true);
                var repositoryDirectoryMethod = XYMLMcpService.class.getDeclaredMethod("repositoryDirectory");
                repositoryDirectoryMethod.setAccessible(true);
                Object launchKey = launchKeyConstructor.newInstance(
                        repositoryDirectoryMethod.invoke(service),
                        validId);
                Class<?> launchStateClass = Class.forName(
                        "space.minecraftstl.xyml.mcp.XYMLMcpService$LaunchState");
                var launchStateConstructor = launchStateClass.getDeclaredConstructor();
                launchStateConstructor.setAccessible(true);
                Object launchState = launchStateConstructor.newInstance();
                var onLog = launchStateClass.getDeclaredMethod("onLog", String.class, boolean.class);
                onLog.setAccessible(true);
                onLog.invoke(launchState, "captured-only diagnostic: " + FABRIC_MISSING_DEPENDENCY_LOG, false);
                launchStates.put(launchKey, launchState);
                assertTrue(launchStates.containsKey(launchKey), launchStates.keySet()::toString);

                Map<String, Object> launcherAnalysis = assertTimeoutPreemptively(
                        Duration.ofSeconds(3),
                        () -> McpTaskExecution.execute(service.analyzeCrash(validId.id(), null, null)));
                Map<String, Object> launcherSolution = firstSolution(launcherAnalysis);
                assertEquals("launcher_latest_log", launcherAnalysis.get("input_source"));
                // The persisted file intentionally contains no matching rule; this diagnosis can therefore only
                // have come from the complete process-capture snapshot merged into the analysis input.
                assertEquals("FABRIC_MISSING_DEPENDENCY", firstDiagnosis(launcherAnalysis).get("result_id"),
                        launcherAnalysis::toString);
                assertEquals(true, launcherSolution.get("mcp_executable"), launcherAnalysis::toString);
                if (!javaDiscoveryReady) {
                    assertTrue(warnings(launcherAnalysis).stream()
                            .anyMatch(warning -> warning.contains("Java runtime discovery is still pending")));
                }

                Map<String, Object> plan = service.planCrashSolution(
                        String.valueOf(launcherAnalysis.get("analysis_id")),
                        String.valueOf(launcherSolution.get("solution_id")));
                Files.writeString(latestLog, "log changed", StandardCharsets.UTF_8);
                String planId = String.valueOf(plan.get("plan_id"));
                Map<String, Object> staleOperation = service.executeCrashSolution(planId);
                Map<String, Object> staleStatus = awaitTerminal(
                        service,
                        String.valueOf(staleOperation.get("operation_id")));
                assertEquals("FAILED", staleStatus.get("status"));
                assertEquals(IllegalStateException.class.getName(), staleStatus.get("failure_type"));
                assertEquals("Crash analysis source could not be revalidated", staleStatus.get("failure_message"));
                assertThrows(IllegalStateException.class, () -> service.executeCrashSolution(planId));
                assertEquals(0, searchTaskCreations.get());

                Files.writeString(latestLog, FABRIC_MISSING_DEPENDENCY_LOG, StandardCharsets.UTF_8);
                Map<String, Object> movedRunAnalysis = McpTaskExecution.execute(
                        service.analyzeCrash(validId.id(), null, null));
                Map<String, Object> movedRunSolution = firstSolution(movedRunAnalysis);
                Map<String, Object> movedRunPlan = service.planCrashSolution(
                        String.valueOf(movedRunAnalysis.get("analysis_id")),
                        String.valueOf(movedRunSolution.get("solution_id")));
                GameSettings.Instance instanceSettings = Objects.requireNonNull(
                        repository.getInstanceGameSettingsOrCreate(validId));
                instanceSettings.getOverrideProperties().add(GameSettings.PROPERTY_RUNNING_DIRECTORY);
                instanceSettings.runningDirectoryProperty().setValue(repositoryRoot.resolve("moved-run").toString());
                Map<String, Object> movedRunOperation = service.executeCrashSolution(
                        String.valueOf(movedRunPlan.get("plan_id")));
                Map<String, Object> movedRunStatus = awaitTerminal(
                        service,
                        String.valueOf(movedRunOperation.get("operation_id")));
                assertEquals("FAILED", movedRunStatus.get("status"));
                assertTrue(String.valueOf(movedRunStatus.get("failure_message")).contains("run directory changed"));
                assertEquals(0, searchTaskCreations.get());

                Map<String, Object> externalAnalysis = McpTaskExecution.execute(service.analyzeCrash(
                        validId.id(), FABRIC_MISSING_DEPENDENCY_LOG, null));
                assertEquals("provided_log", externalAnalysis.get("input_source"));
                assertEquals(false, firstSolution(externalAnalysis).get("mcp_executable"));

                String nativeMemoryLog = "Native memory allocation (mmap) failed to commit 1048576 bytes\n"
                        + "java.lang.OutOfMemoryError: Java heap space";
                Map<String, Object> memoryAnalysis = McpTaskExecution.execute(
                        service.analyzeCrash(validId.id(), nativeMemoryLog, null));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> suppressedMatches =
                        (List<Map<String, Object>>) memoryAnalysis.get("suppressed_matches");
                Map<String, Object> outOfMemory = suppressedMatches.stream()
                        .filter(match -> "OUT_OF_MEMORY".equals(match.get("rule")))
                        .findFirst()
                        .orElseThrow();
                assertEquals("VIRTUAL_MEMORY", outOfMemory.get("suppressed_by"));
                assertThrows(UnsupportedOperationException.class,
                        () -> outOfMemory.put("suppressed_by", "unexpected"));

                removeLoadedInstance(repository, parentId);
                Map<String, Object> degradedAnalysis = McpTaskExecution.execute(service.analyzeCrash(
                        brokenId.id(), FABRIC_MISSING_DEPENDENCY_LOG, null));
                assertTrue(degradedAnalysis.containsKey("matches"));
                assertEquals("FABRIC_MISSING_DEPENDENCY", firstDiagnosis(degradedAnalysis).get("result_id"));
                assertTrue(warnings(degradedAnalysis).stream()
                        .anyMatch(warning -> warning.contains("log-only metadata")));
            }
        } finally {
            gameSettingsPresetsField.set(null, previousGameSettingsPresets);
            launcherSettingsField.set(null, previousLauncherSettings);
        }
    }

    /// Creates one repository bound to the supplied temporary root.
    ///
    /// @param root temporary repository root
    /// @return unrefreshed repository
    private static XYMLGameRepository repository(Path root) {
        return new XYMLGameRepository(new GameDirectory(
                GameDirectoryID.generate(),
                LocalizedText.plain("MCP crash analysis test"),
                PortablePath.of(root.toString())));
    }

    /// Writes one instance manifest to its repository-owned path.
    ///
    /// @param repository fixture repository
    /// @param manifest manifest to write
    /// @throws Exception when the fixture cannot be written
    private static void writeManifest(XYMLGameRepository repository, GameInstanceManifest manifest) throws Exception {
        Path target = repository.getInstanceJson(manifest.id());
        Files.createDirectories(target.getParent());
        JsonUtils.writeToJsonFile(target, manifest);
    }

    /// Simulates a parent disappearing from a previously loaded repository snapshot.
    ///
    /// @param repository fixture repository
    /// @param instanceId loaded parent to remove
    /// @throws ReflectiveOperationException when the repository implementation changes unexpectedly
    @SuppressWarnings("unchecked")
    private static void removeLoadedInstance(XYMLGameRepository repository, GameInstanceID instanceId)
            throws ReflectiveOperationException {
        Field statusField = DefaultGameRepository.class.getDeclaredField("status");
        statusField.setAccessible(true);
        Object status = statusField.get(repository);
        Field instancesField = status.getClass().getDeclaredField("instances");
        instancesField.setAccessible(true);
        Map<GameInstanceID, Object> instances = (Map<GameInstanceID, Object>) instancesField.get(status);
        instances.remove(instanceId);
    }

    /// Extracts the first structured diagnosis.
    ///
    /// @param analysis service analysis response
    /// @return first XYAT diagnosis
    @SuppressWarnings("unchecked")
    private static @Unmodifiable Map<String, Object> firstDiagnosis(Map<String, Object> analysis) {
        List<Map<String, Object>> diagnoses = (List<Map<String, Object>>) analysis.get("diagnoses");
        assertFalse(diagnoses.isEmpty());
        return diagnoses.get(0);
    }

    /// Extracts the first diagnosis's structured solution.
    ///
    /// @param analysis service analysis response
    /// @return first repair solution
    @SuppressWarnings("unchecked")
    private static @Unmodifiable Map<String, Object> firstSolution(Map<String, Object> analysis) {
        return (Map<String, Object>) firstDiagnosis(analysis).get("solution");
    }

    /// Extracts immutable non-fatal warnings from an analysis response.
    ///
    /// @param analysis service analysis response
    /// @return immutable warning strings
    @SuppressWarnings("unchecked")
    private static @Unmodifiable List<String> warnings(Map<String, Object> analysis) {
        return (List<String>) analysis.get("warnings");
    }

    /// Polls one asynchronous crash-repair operation until it reaches a terminal state.
    ///
    /// @param service service owning the operation
    /// @param operationId opaque operation identifier
    /// @return immutable terminal status
    /// @throws InterruptedException when polling is interrupted
    private static @Unmodifiable Map<String, Object> awaitTerminal(
            XYMLMcpService service,
            String operationId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            Map<String, Object> status = service.getCrashRepairStatus(operationId);
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
}
