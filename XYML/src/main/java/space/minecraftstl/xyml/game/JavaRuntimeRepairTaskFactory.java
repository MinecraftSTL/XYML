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
package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.java.JavaManager;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.platform.Platform;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.util.Objects;

/// Builds application-level Java replacement tasks for crash-analysis solvers.
@NotNullByDefault
public final class JavaRuntimeRepairTaskFactory {
    /// Prevents construction of this static task factory.
    private JavaRuntimeRepairTaskFactory() {
    }

    /// Creates a stopped task that replaces an incompatible launch runtime and persists the replacement selection.
    ///
    /// A launcher-managed runtime is removed first. The task then reuses a compatible discovered runtime when possible,
    /// otherwise downloads the runtime recommended by the instance manifest or vanilla game version. The final runtime
    /// is selected only for the affected instance.
    ///
    /// @param repository repository owning the launched instance and its settings
    /// @param manifest launched instance manifest
    /// @param currentJava incompatible runtime used by the failed launch
    /// @return stopped task that resolves and selects a compatible Java runtime
    public static Task<@Nullable Void> create(
            XYMLGameRepository repository,
            GameInstanceManifest manifest,
            JavaRuntime currentJava) {
        XYMLGameRepository checkedRepository = Objects.requireNonNull(repository, "repository");
        GameInstanceManifest checkedManifest = Objects.requireNonNull(manifest, "manifest");
        JavaRuntime checkedCurrentJava = Objects.requireNonNull(currentJava, "currentJava");
        GameVersionNumber gameVersion = GameVersionNumber.asGameVersion(
                checkedRepository.getGameVersion(checkedManifest));

        Task<@Nullable Void> uninstallTask = checkedCurrentJava.isManaged()
                ? JavaManager.getUninstallJavaTask(checkedCurrentJava)
                : Task.completed(null);
        return uninstallTask
                .thenComposeAsync(() -> findOrInstallJava(checkedRepository, checkedManifest, gameVersion))
                .thenAcceptAsync(
                        Schedulers.ui(),
                        (@Nullable JavaRuntime replacement) -> selectJava(
                                checkedRepository,
                                checkedManifest,
                                Objects.requireNonNull(replacement, "replacement Java runtime")));
    }

    /// Resolves an installed compatible runtime or creates a task that downloads the recommended runtime.
    ///
    /// @param repository repository supplying the active download provider
    /// @param manifest launched instance manifest
    /// @param gameVersion parsed Minecraft version
    /// @return stopped task yielding a compatible runtime
    private static Task<JavaRuntime> findOrInstallJava(
            XYMLGameRepository repository,
            GameInstanceManifest manifest,
            GameVersionNumber gameVersion) {
        return Task.supplyAsync(() -> JavaManager.findSuitableJava(gameVersion, manifest))
                .thenComposeAsync((@Nullable JavaRuntime installedJava) -> {
                    if (installedJava != null) {
                        return Task.completed(installedJava);
                    }

                    @Nullable GameJavaVersion targetJava = manifest.javaVersion();
                    if (targetJava == null) {
                        targetJava = GameJavaVersion.getMinimumJavaVersion(gameVersion);
                    }
                    if (targetJava == null) {
                        targetJava = GameJavaVersion.JAVA_8;
                    }
                    return JavaManager.getDownloadJavaTask(
                            repository.getDependency().getDownloadProvider(),
                            Platform.SYSTEM_PLATFORM,
                            targetJava);
                });
    }

    /// Selects the replacement runtime for the affected writable instance.
    ///
    /// @param repository repository owning the instance settings
    /// @param manifest launched instance manifest
    /// @param replacement compatible replacement runtime
    /// @throws IllegalStateException when the instance settings cannot be updated safely
    private static void selectJava(
            XYMLGameRepository repository,
            GameInstanceManifest manifest,
            JavaRuntime replacement) {
        GameInstanceID instanceId = manifest.id();
        if (repository.isInstanceGameSettingsReadOnly(instanceId)) {
            throw new IllegalStateException("Cannot update read-only game settings for " + instanceId);
        }

        GameSettings.@Nullable Instance setting = repository.getInstanceGameSettingsOrCreate(instanceId);
        if (setting == null) {
            throw new IllegalStateException("Cannot create game settings for " + instanceId);
        }
        setting.getOverrideProperties().add(GameSettings.PROPERTY_JAVA_TYPE);
        setting.getOverrideProperties().add(GameSettings.PROPERTY_DETECTED_JAVA);
        setting.javaTypeProperty().setValue(JavaVersionType.DETECTED);
        setting.detectedJavaProperty().setValue(GameSettings.DetectedJava.of(replacement));
        repository.saveGameSettings(instanceId);
    }
}
