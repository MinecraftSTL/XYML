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
import space.minecraftstl.xyml.task.Task;
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
                .setName("Import Minecraft instance JSON");
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
        Task<?> optionalAssetsAndLibraries = Task.allOf(
                new GameAssetDownloadTask(
                        dependencyManager,
                        importedManifest,
                        GameAssetDownloadTask.DOWNLOAD_INDEX_FORCIBLY,
                        true),
                new GameLibrariesTask(dependencyManager, importedManifest, true))
                .withRunAsync(ioExecutor, () -> {
                    // Match the import contract: core game download and JSON save remain fatal,
                    // while optional asset/library repair can be retried from instance maintenance.
                });

        return Task.allOf(
                        new GameDownloadTask(dependencyManager, null, importedManifest),
                        optionalAssetsAndLibraries)
                .thenComposeAsync(ioExecutor, ignored -> repository.saveAsync(importedManifest))
                .thenRunAsync(ioExecutor, repository::refresh)
                .thenRunAsync(ioExecutor, () -> repository.setSelectedInstance(instanceId));
    }
}
