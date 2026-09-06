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
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.function.ExceptionalRunnable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;

/// Creates resource-protected revalidation tasks for launcher-owned crash sources.
@NotNullByDefault
final class XYMLMcpCrashSourceValidationTask {
    /// Prevents construction of this static task factory.
    private XYMLMcpCrashSourceValidationTask() {
    }

    /// Creates a fresh stopped task that proves the analyzed instance source is still current.
    ///
    /// The captured instance, run directory, and settings paths are all protected before the task resolves current
    /// state. This lets the same task participate in both the initial repair gate and a Java repair's final commit.
    ///
    /// @param repository repository owning the analyzed instance
    /// @param context immutable source snapshot captured during analysis
    /// @param expectedFingerprint expected SHA-256 fingerprint of the analyzed log
    /// @param launchGuard rejects repair while the analyzed instance is still running
    /// @param currentRunDirectory resolves the current run directory under protected settings resources
    /// @param instanceSettingsFile captured instance-specific settings path
    /// @return fresh stopped validation task with concrete resources
    static Task<@Nullable Void> create(
            XYMLGameRepository repository,
            XYMLMcpCrashAnalysisSupport.Context context,
            String expectedFingerprint,
            ExceptionalRunnable<?> launchGuard,
            Callable<Path> currentRunDirectory,
            Path instanceSettingsFile) {
        XYMLGameRepository checkedRepository = Objects.requireNonNull(repository, "repository");
        XYMLMcpCrashAnalysisSupport.Context checkedContext = Objects.requireNonNull(context, "context");
        String checkedFingerprint = Objects.requireNonNull(expectedFingerprint, "expectedFingerprint");
        ExceptionalRunnable<?> checkedLaunchGuard = Objects.requireNonNull(launchGuard, "launchGuard");
        Callable<Path> checkedRunDirectory = Objects.requireNonNull(currentRunDirectory, "currentRunDirectory");
        Path checkedSettingsFile = Objects.requireNonNull(instanceSettingsFile, "instanceSettingsFile")
                .toAbsolutePath().normalize();
        return Task.runAsync("Revalidate MCP crash source", Schedulers.io(), () ->
                checkedRepository.callWithStableBaseDirectory(checkedContext.repositoryDirectory(), () -> {
                    validate(checkedRepository, checkedContext, checkedFingerprint,
                            checkedLaunchGuard, checkedRunDirectory);
                    return null;
                })).setResources(
                TaskResource.repositoryOperation(checkedContext.repositoryDirectory()),
                TaskResource.gameInstance(checkedContext.instanceDirectory()),
                TaskResource.gameDirectory(checkedContext.runDirectory()),
                TaskResource.configuration(checkedSettingsFile),
                TaskResource.configuration(SettingsManager.gameSettingsLocation()),
                TaskResource.configuration(SettingsManager.settingsLocation()));
    }

    /// Revalidates repository identity, current instance state, current run directory, manifest, and log contents.
    ///
    /// @param repository repository owning the analyzed instance
    /// @param context immutable source snapshot captured during analysis
    /// @param expectedFingerprint expected SHA-256 fingerprint
    /// @param launchGuard rejects repair while the analyzed instance is still running
    /// @param currentRunDirectory resolves the current protected run directory
    /// @throws Exception when the source cannot be read or no longer matches
    private static void validate(
            XYMLGameRepository repository,
            XYMLMcpCrashAnalysisSupport.Context context,
            String expectedFingerprint,
            ExceptionalRunnable<?> launchGuard,
            Callable<Path> currentRunDirectory) throws Exception {
        requirePath(context.instanceDirectory(), repository.getInstanceRoot(context.instanceId()), "instance directory");
        if (!repository.hasInstance(context.instanceId())) {
            throw new IllegalArgumentException("Unknown instance: " + context.instanceId().id());
        }
        launchGuard.run();
        requirePath(context.runDirectory(), currentRunDirectory.call(), "instance run directory");
        try {
            GameInstanceManifest manifest = context.manifest();
            if (manifest != null
                    && !manifest.equals(repository.getResolvedInstanceManifest(context.instanceId()).launchManifest())) {
                throw new IOException("The instance manifest changed after crash analysis");
            }
            String actualFingerprint = XYMLMcpCrashAnalysisSupport.fingerprint(readLog(context.runDirectory()));
            if (!actualFingerprint.equals(expectedFingerprint)) {
                throw new IOException("The instance latest log changed after crash analysis");
            }
        } catch (IOException validationFailure) {
            throw new IllegalStateException("Crash analysis source could not be revalidated", validationFailure);
        }
    }

    /// Rejects a captured path when current repository state resolves it elsewhere.
    ///
    /// @param expected captured normalized path
    /// @param actual current path
    /// @param description path description for diagnostics
    private static void requirePath(Path expected, Path actual, String description) {
        if (!expected.equals(actual.toAbsolutePath().normalize())) {
            throw new IllegalStateException(description + " changed while waiting for resources");
        }
    }

    /// Reads the preferred launcher log from one captured run directory.
    ///
    /// @param runDirectory captured run directory
    /// @return UTF-8 log text, or an empty string when neither log path exists
    /// @throws IOException if the selected log cannot be read
    private static String readLog(Path runDirectory) throws IOException {
        Path latest = runDirectory.resolve("logs/latest.log");
        Path path = Files.exists(latest) ? latest : runDirectory.resolve("latest.log");
        return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    }
}
