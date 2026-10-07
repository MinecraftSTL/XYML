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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.game.migration.AutomaticInstanceConfigMigrationTaskFactory;
import space.minecraftstl.xyml.game.migration.InstanceConfigMigrationRequest;
import space.minecraftstl.xyml.game.migration.InstanceConfigMigrationResult;
import space.minecraftstl.xyml.game.migration.InstanceConfigMigrationService;
import space.minecraftstl.xyml.setting.GameDirectoryManager;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.setting.InstanceConfigMigrationPolicy;
import space.minecraftstl.xyml.setting.InstanceConfigMigrationSourceType;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;


/// Resolves the target instance's parent-preset migration policy and creates resource-aware filesystem tasks.
@NotNullByDefault
public final class LauncherAutomaticInstanceConfigMigrationTaskFactory
        implements AutomaticInstanceConfigMigrationTaskFactory {
    /// Executor used for repository refresh and filesystem work.
    private final Executor executor;

    /// Creates a production automatic migration factory.
    ///
    /// @param executor background executor used for all blocking work
    public LauncherAutomaticInstanceConfigMigrationTaskFactory(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /// Creates migration only when the installed instance is present and effectively isolated.
    @Override
    public Task<InstanceConfigMigrationResult> createAfterInstall(
            XYMLGameRepository repository,
            GameInstanceID instanceId) {
        XYMLGameRepository checkedRepository = Objects.requireNonNull(repository, "repository");
        GameInstanceID checkedInstanceId = Objects.requireNonNull(instanceId, "instanceId");
        return Task.composeAsync(executor, () -> {
            if (!checkedRepository.hasInstance(checkedInstanceId)
                    || !checkedRepository.isInstanceIsolated(checkedInstanceId)) {
                return emptyResult();
            }
            return createResolvedTask(
                    checkedRepository,
                    checkedInstanceId,
                    checkedRepository.getRunDirectory(checkedInstanceId),
                    checkedRepository.getInstanceGameSettings(checkedInstanceId));
        }).asOrchestration();
    }

    /// Creates migration using the instance's currently persisted parent preset.
    @Override
    public Task<InstanceConfigMigrationResult> createBeforeIsolation(
            XYMLGameRepository repository,
            GameInstanceID instanceId,
            Path targetDirectory) {
        return createIsolationTask(repository, instanceId, targetDirectory,
                repository.getInstanceGameSettings(instanceId));
    }

    /// Creates migration using the parent preset selected in the unsaved isolation settings.
    @Override
    public Task<InstanceConfigMigrationResult> createBeforeIsolation(
            XYMLGameRepository repository,
            GameInstanceID instanceId,
            Path targetDirectory,
            @Nullable GameSettingsPresetID parentPresetId) {
        GameSettings.Instance candidate = new GameSettings.Instance();
        candidate.parentProperty().setValue(parentPresetId);
        return createIsolationTask(repository, instanceId, targetDirectory, candidate);
    }

    /// Creates an unstarted migration without changing the persisted instance settings.
    private Task<InstanceConfigMigrationResult> createIsolationTask(
            XYMLGameRepository repository,
            GameInstanceID instanceId,
            Path targetDirectory,
            @Nullable GameSettings.Instance candidate) {
        XYMLGameRepository checkedRepository = Objects.requireNonNull(repository, "repository");
        GameInstanceID checkedInstanceId = Objects.requireNonNull(instanceId, "instanceId");
        Path checkedTarget = Objects.requireNonNull(targetDirectory, "targetDirectory").toAbsolutePath().normalize();
        return Task.composeAsync(executor, () -> {
            if (!checkedRepository.hasInstance(checkedInstanceId)) {
                throw new IllegalStateException("Automatic migration target instance is unavailable: " + checkedInstanceId);
            }
            return createResolvedTask(checkedRepository, checkedInstanceId, checkedTarget, candidate);
        }).asOrchestration();
    }

    /// Captures the policy and exact source repository on the EDT before creating blocking tasks.
    private Task<InstanceConfigMigrationResult> createResolvedTask(
            XYMLGameRepository targetRepository,
            GameInstanceID targetInstanceId,
            Path targetDirectory,
            @Nullable GameSettings.Instance candidate) {
        CapturedPolicy captured = capturePolicy(targetRepository, candidate);
        InstanceConfigMigrationPolicy policy = captured.policy();
        if (!policy.active()) {
            return emptyResult();
        }

        @Nullable XYMLGameRepository sourceRepository = captured.sourceRepository();
        if (sourceRepository == null) {
            Path sourceDirectory = targetRepository.resolveSharedRunDirectory(targetInstanceId, candidate);
            return createFileTask(policy, sourceDirectory, targetDirectory);
        }

        Path sourceRepositoryDirectory = sourceRepository.getBaseDirectory().toAbsolutePath().normalize();
        return Task.runAsync("Refresh instance configuration migration source", executor, sourceRepository::refresh)
                .setResources(TaskResource.gameDirectory(sourceRepositoryDirectory))
                .thenComposeAsync(executor, () -> {
                    GameInstanceID sourceInstance = Objects.requireNonNull(
                            policy.sourceInstance(), "instance migration source");
                    if (!sourceRepository.hasInstance(sourceInstance)) {
                        throw new IllegalStateException(
                                "Automatic migration source instance is unavailable: " + sourceInstance);
                    }
                    if (!sourceRepository.isInstanceIsolated(sourceInstance)) {
                        throw new IllegalStateException(
                                "Automatic migration source instance is no longer isolated: " + sourceInstance);
                    }
                    return createFileTask(
                            policy,
                            sourceRepository.getRunDirectory(sourceInstance),
                            targetDirectory);
                })
                .asOrchestration();
    }

    /// Captures the selected parent preset policy and source identity on the Swing event thread.
    private static CapturedPolicy capturePolicy(
            XYMLGameRepository repository,
            @Nullable GameSettings.Instance candidate) {
        AtomicReference<CapturedPolicy> captured = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            @Nullable InstanceConfigMigrationPolicy configured =
                    repository.getParentGameSettings(candidate).instanceConfigMigrationPolicyProperty().getValue();
            InstanceConfigMigrationPolicy policy = Objects.requireNonNullElse(
                    configured, InstanceConfigMigrationPolicy.defaults());
            @Nullable XYMLGameRepository sourceRepository = null;
            if (policy.active() && policy.sourceType() == InstanceConfigMigrationSourceType.INSTANCE) {
                sourceRepository = GameDirectoryManager.getRepository(Objects.requireNonNull(
                        policy.sourceGameDirectory(), "instance migration source game directory"));
            }
            captured.set(new CapturedPolicy(policy, sourceRepository));
        });
        return Objects.requireNonNull(captured.get(), "captured migration policy");
    }

    /// Creates one non-replacing filesystem migration with source and target resource declarations.
    private Task<InstanceConfigMigrationResult> createFileTask(
            InstanceConfigMigrationPolicy policy,
            Path sourceDirectory,
            Path targetDirectory) {
        InstanceConfigMigrationRequest request = new InstanceConfigMigrationRequest(
                sourceDirectory,
                targetDirectory,
                policy.contents(),
                false);
        return Task.supplyAsync(
                        "Migrate isolated instance configuration",
                        executor,
                        () -> InstanceConfigMigrationService.migrate(request))
                .setResources(
                        TaskResource.gameDirectory(request.sourceDirectory()),
                        TaskResource.gameInstance(request.targetDirectory()));
    }

    /// Returns a completed no-op result for disabled or inapplicable automatic migration.
    private static Task<InstanceConfigMigrationResult> emptyResult() {
        return Task.completed(new InstanceConfigMigrationResult(Map.of()));
    }

    /// Immutable EDT-captured policy and optional exact source repository.
    ///
    /// @param policy parent-preset policy snapshot
    /// @param sourceRepository exact source repository, or null for the global source strategy
    private record CapturedPolicy(
            InstanceConfigMigrationPolicy policy,
            @Nullable XYMLGameRepository sourceRepository) {
        /// Rejects a missing policy.
        private CapturedPolicy {
            Objects.requireNonNull(policy, "policy");
        }
    }
}
