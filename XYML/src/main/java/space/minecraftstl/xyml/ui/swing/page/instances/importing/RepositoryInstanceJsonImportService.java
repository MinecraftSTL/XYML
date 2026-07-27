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
import space.minecraftstl.xyml.game.Version;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.task.Task;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;

/// Imports a version JSON through the selected repository's established download and save tasks.
///
/// The returned root is a deferred composition scheduled on the caller-owned I/O executor. JSON
/// parsing, conflict checks, dependency-manager creation, version resolution, and child-task
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
    /// @param source local Minecraft version JSON path
    /// @param instanceId destination instance ID
    /// @return cancellable import task
    @Override
    public Task<@Nullable Void> createImportTask(Path source, String instanceId) {
        Path normalizedSource = Objects.requireNonNull(source, "source")
                .toAbsolutePath()
                .normalize();
        String normalizedInstanceId = Objects.requireNonNull(instanceId, "instanceId").strip();
        return Task.<@Nullable Void>composeAsync(
                ioExecutor,
                () -> prepareImport(normalizedSource, normalizedInstanceId))
                .setName("Import Minecraft instance JSON");
    }

    /// Parses and validates one source, then creates the established download/save chain.
    ///
    /// @param source normalized local JSON source
    /// @param instanceId stripped destination instance ID
    /// @return task chain ready for executor attachment
    /// @throws InstanceJsonImportException when validation or JSON parsing fails
    private Task<@Nullable Void> prepareImport(Path source, String instanceId)
            throws InstanceJsonImportException {
        if (!XYMLGameRepository.isValidVersionId(instanceId)) {
            throw InstanceJsonImportException.invalidInstanceId(instanceId);
        }
        if (repository.versionIdConflicts(instanceId)) {
            throw InstanceJsonImportException.instanceAlreadyExists(instanceId);
        }

        final Version parsedVersion;
        try {
            parsedVersion = repository.readVersionJson(source);
        } catch (IOException | RuntimeException parseFailure) {
            throw InstanceJsonImportException.malformedJson(source, parseFailure);
        }

        Version importedVersion = parsedVersion.setId(instanceId).setJar(instanceId);
        DefaultDependencyManager dependencyManager = repository.getDependency();
        Task<?> optionalAssetsAndLibraries = Task.allOf(
                new GameAssetDownloadTask(
                        dependencyManager,
                        importedVersion,
                        GameAssetDownloadTask.DOWNLOAD_INDEX_FORCIBLY,
                        true),
                new GameLibrariesTask(dependencyManager, importedVersion, true))
                .withRunAsync(ioExecutor, () -> {
                    // Match the legacy import contract: core game download and JSON save remain fatal,
                    // while optional asset/library repair can be retried from instance maintenance.
                });

        return Task.allOf(
                        new GameDownloadTask(dependencyManager, null, importedVersion),
                        optionalAssetsAndLibraries)
                .thenComposeAsync(ioExecutor, ignored -> repository.saveAsync(importedVersion))
                .thenRunAsync(ioExecutor, repository::refreshVersions)
                .thenRunAsync(ioExecutor, () -> repository.setSelectedInstance(instanceId));
    }
}
