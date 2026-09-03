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
import space.minecraftstl.xyml.util.PortablePath;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
                Files.writeString(latestLog, FABRIC_MISSING_DEPENDENCY_LOG, StandardCharsets.UTF_8);
                boolean javaDiscoveryReady = JavaManager.isInitialized();

                Map<String, Object> launcherAnalysis = assertTimeoutPreemptively(
                        Duration.ofSeconds(3),
                        () -> service.analyzeCrash(validId.id(), null, null));
                Map<String, Object> launcherSolution = firstSolution(launcherAnalysis);
                assertEquals("launcher_latest_log", launcherAnalysis.get("input_source"));
                assertEquals(true, launcherSolution.get("mcp_executable"));
                if (!javaDiscoveryReady) {
                    assertTrue(warnings(launcherAnalysis).stream()
                            .anyMatch(warning -> warning.contains("Java runtime discovery is still pending")));
                }

                Map<String, Object> plan = service.planCrashSolution(
                        String.valueOf(launcherAnalysis.get("analysis_id")),
                        String.valueOf(launcherSolution.get("solution_id")));
                Files.writeString(latestLog, "log changed", StandardCharsets.UTF_8);
                String planId = String.valueOf(plan.get("plan_id"));
                assertThrows(IllegalStateException.class, () -> service.executeCrashSolution(planId));
                assertThrows(IllegalStateException.class, () -> service.executeCrashSolution(planId));
                assertEquals(0, searchTaskCreations.get());

                Map<String, Object> externalAnalysis = service.analyzeCrash(
                        validId.id(), FABRIC_MISSING_DEPENDENCY_LOG, null);
                assertEquals("provided_log", externalAnalysis.get("input_source"));
                assertEquals(false, firstSolution(externalAnalysis).get("mcp_executable"));

                removeLoadedInstance(repository, parentId);
                Map<String, Object> degradedAnalysis = service.analyzeCrash(
                        brokenId.id(), FABRIC_MISSING_DEPENDENCY_LOG, null);
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
}
