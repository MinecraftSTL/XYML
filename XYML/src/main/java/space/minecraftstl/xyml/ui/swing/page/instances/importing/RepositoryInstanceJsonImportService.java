// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.importing;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.game.GameAssetDownloadTask;
import space.minecraftstl.xyml.download.game.GameDownloadTask;
import space.minecraftstl.xyml.download.game.GameLibrariesTask;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.gson.JsonUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;

/// Imports an instance manifest JSON through the selected repository's established download and save tasks.
///
/// The returned root is a deferred composition scheduled on the caller-owned I/O executor. JSON
/// parsing, conflict checks, dependency-manager creation, manifest resolution, and child-task
/// construction therefore never run on the Swing event-dispatch thread.
@NotNullByDefault
public final class RepositoryInstanceJsonImportService implements InstanceJsonImportService {
    /// Repository captured when the import window is created.
    private final XYMLGameRepository repository;

    /// Executor used for preparation and repository continuations.
    private final Executor ioExecutor;

    /// Creates a repository-backed import service.
    ///
    /// @param repository destination game repository
    /// @param ioExecutor executor used for blocking parsing and task preparation
    public RepositoryInstanceJsonImportService(
            XYMLGameRepository repository,
            Executor ioExecutor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    /// Creates a deferred import chain whose full preparation runs on the I/O executor.
    ///
    /// @param source local game instance manifest JSON path
    /// @param instanceId destination instance ID
    /// @return cancellable import task
    @Override
    public Task<@Nullable Void> createImportTask(Path source, GameInstanceID instanceId) {
        Path normalizedSource = Objects.requireNonNull(source, "source")
                .toAbsolutePath()
                .normalize();
        GameInstanceID targetId = Objects.requireNonNull(instanceId, "instanceId");
        return Task.<@Nullable Void>composeAsync(
                ioExecutor,
                () -> prepareImport(normalizedSource, targetId))
                .setName("Import Minecraft instance JSON")
                .setResources(
                        TaskResource.repositoryMetadata(repository.getBaseDirectory()),
                        TaskResource.configuration(normalizedSource))
                .releaseResourcesBeforeDependencies();
    }

    /// Parses and validates one source, then creates the established download/save chain.
    ///
    /// @param source normalized local JSON source
    /// @param instanceId destination instance ID
    /// @return task chain ready for executor attachment
    /// @throws InstanceJsonImportException when validation or JSON parsing fails
    private Task<@Nullable Void> prepareImport(Path source, GameInstanceID instanceId)
            throws InstanceJsonImportException {
        if (repository.instanceIdConflicts(instanceId)) {
            throw InstanceJsonImportException.instanceAlreadyExists(instanceId);
        }

        final GameInstanceManifest parsedManifest;
        try {
            parsedManifest = Objects.requireNonNull(
                    JsonUtils.fromJsonFile(source, GameInstanceManifest.class),
                    "instance manifest");
        } catch (IOException | RuntimeException parseFailure) {
            throw InstanceJsonImportException.malformedJson(source, parseFailure);
        }

        GameInstanceManifest importedManifest = parsedManifest.withId(instanceId).withJar(instanceId);
        DefaultDependencyManager dependencyManager = repository.getDependency();
        TaskResource metadataResource = TaskResource.repositoryMetadata(repository.getBaseDirectory());
        TaskResource operationResource = TaskResource.repositoryOperation(repository.getBaseDirectory());
        TaskResource instanceResource = TaskResource.gameInstance(repository.getInstanceRoot(instanceId));
        Task<@Nullable Void> availabilityCheck = Task.runAsync(
                "Recheck imported instance name",
                ioExecutor,
                () -> {
                    if (repository.instanceIdConflicts(instanceId)) {
                        throw InstanceJsonImportException.instanceAlreadyExists(instanceId);
                    }
                }).setResources(metadataResource, instanceResource);
        Task<?> optionalAssetsAndLibraries = Task.allOf(
                new GameAssetDownloadTask(
                        dependencyManager,
                        importedManifest,
                        GameAssetDownloadTask.DOWNLOAD_INDEX_FORCIBLY,
                        true),
                new GameLibrariesTask(dependencyManager, importedManifest, true));
        Task<@Nullable Void> ignoredOptionalFailure = ignoreOptionalDownloadFailure(
                optionalAssetsAndLibraries,
                ioExecutor);

        Task<?> downloads = Task.allOf(
                new GameDownloadTask(dependencyManager, null, importedManifest),
                ignoredOptionalFailure);
        Task<?> operation = availabilityCheck
                .thenComposeAsync(ioExecutor, ignored -> downloads)
                .setResources(operationResource, instanceResource)
                .thenComposeAsync(ioExecutor, ignored -> repository.saveAsync(importedManifest))
                .setResources(operationResource, instanceResource);
        Task<@Nullable Void> refreshed = operation.thenComposeAsync(ioExecutor, ignored -> Task.runAsync(
                        "Publish imported instance",
                        ioExecutor,
                        repository::refresh)
                .setResources(TaskResource.gameDirectory(repository.getBaseDirectory())))
                .asOrchestration();
        return refreshed.thenComposeAsync(ioExecutor, ignored -> Task.runAsync(
                        "Select imported instance",
                        ioExecutor,
                        () -> repository.setSelectedInstance(instanceId))
                .setResources(TaskResource.configuration(SettingsManager.settingsLocation())))
                .asOrchestration();
    }

    /// Converts optional asset and library completion into a resource-free best-effort barrier.
    ///
    /// The fixed-task compose overload lets the successor run after predecessor failure and classifies both the
    /// continuation and its already-completed successor as orchestration. Core game download and JSON persistence stay
    /// fatal; optional repair can still be retried from instance maintenance.
    ///
    /// @param optionalDownloads optional asset and library task group
    /// @param executor executor retained for the completed successor
    /// @return successful orchestration barrier even when optional downloads fail
    static Task<@Nullable Void> ignoreOptionalDownloadFailure(Task<?> optionalDownloads, Executor executor) {
        return Objects.requireNonNull(optionalDownloads, "optionalDownloads").withComposeAsync(
                Task.<@Nullable Void>completed(null).setExecutor(Objects.requireNonNull(executor, "executor")));
    }
}
