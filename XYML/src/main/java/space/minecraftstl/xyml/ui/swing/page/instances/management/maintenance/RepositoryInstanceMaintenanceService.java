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
package space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.download.game.GameAssetDownloadTask;
import space.minecraftstl.xyml.game.ModpackHelper;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.modpack.ModpackConfiguration;
import space.minecraftstl.xyml.modpack.server.ServerModpackManifest;
import space.minecraftstl.xyml.task.FileDownloadTask;
import space.minecraftstl.xyml.task.GetTask;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/// Adapts established repository, modpack, download, and cleanup APIs for Swing maintenance controls.
///
/// Destructive operations use only repository-derived fixed paths. [FileUtils#deleteDirectory(Path)] unlinks
/// a directory symlink instead of traversing it, while every operation runs on the supplied worker executor.
@NotNullByDefault
public final class RepositoryInstanceMaintenanceService implements InstanceMaintenanceService {
    /// Repository owning the fixed instance and shared game data.
    private final XYMLGameRepository repository;

    /// Stable fixed instance identifier.
    private final GameInstanceID instanceId;

    /// Caller-owned executor for filesystem work and task composition.
    private final Executor ioExecutor;

    /// Creates a service using the launcher's shared I/O scheduler.
    ///
    /// @param repository repository containing the fixed instance
    /// @param instanceId stable fixed instance identifier
    public RepositoryInstanceMaintenanceService(
            XYMLGameRepository repository,
            GameInstanceID instanceId) {
        this(repository, instanceId, Schedulers.io());
    }

    /// Creates a service with an explicit worker for deterministic tests.
    ///
    /// @param repository repository containing the fixed instance
    /// @param instanceId stable fixed instance identifier
    /// @param ioExecutor caller-owned executor for blocking work
    RepositoryInstanceMaintenanceService(
            XYMLGameRepository repository,
            GameInstanceID instanceId,
            Executor ioExecutor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    /// Reads current filesystem presence and modpack eligibility on the worker.
    ///
    /// @return asynchronous authoritative snapshot
    @Override
    public CompletionStage<InstanceMaintenanceSnapshot> loadSnapshot() {
        return CompletableFuture.supplyAsync(this::readSnapshot, ioExecutor);
    }

    /// Defers archive validation and Core update-task construction to the worker.
    ///
    /// @param archive local update archive
    /// @param charset archive entry-name charset
    /// @return stopped update, repository-refresh, and snapshot task
    @Override
    public Task<InstanceMaintenanceSnapshot> updateModpack(Path archive, Charset charset) {
        Path updateArchive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        Charset archiveCharset = Objects.requireNonNull(charset, "charset");
        return Task.composeAsync(ioExecutor, () -> {
            requireExistingInstance();
            if (!repository.isModpack(instanceId)) {
                throw new IllegalStateException("The selected instance is not an updateable modpack");
            }
            if (!Files.isRegularFile(updateArchive) || !ModpackHelper.isFileModpackByExtension(updateArchive)) {
                throw new IllegalArgumentException("Unsupported modpack archive: " + updateArchive);
            }
            ModpackConfiguration<?> configuration = ModpackHelper.readModpackConfiguration(
                    repository.getModpackConfiguration(instanceId));
            Task<?> updateTask = ModpackHelper.getUpdateTask(
                    repository,
                    updateArchive,
                    archiveCharset,
                    instanceId,
                    configuration);
            return updateTask.thenComposeAsync(ioExecutor, this::snapshotTask);
        });
    }

    /// Downloads a direct archive or reads a server manifest before applying the established provider update.
    ///
    /// @param source direct HTTP or HTTPS update source
    /// @return stopped remote update, repository-refresh, and snapshot task
    @Override
    public Task<InstanceMaintenanceSnapshot> updateModpack(URI source) {
        URI updateSource = requireHttpSource(source);
        return Task.composeAsync(ioExecutor, () -> {
            requireExistingInstance();
            if (!repository.isModpack(instanceId)) {
                throw new IllegalStateException("The selected instance is not an updateable modpack");
            }
            ModpackConfiguration<?> configuration = ModpackHelper.readModpackConfiguration(
                    repository.getModpackConfiguration(instanceId));
            if (isServerManifestSource(updateSource)) {
                return new GetTask(updateSource)
                        .thenGetJsonAsync(ServerModpackManifest.class)
                        .thenComposeAsync(ioExecutor, manifest -> ModpackHelper.getUpdateTask(
                                repository,
                                Objects.requireNonNull(manifest, "remote server manifest"),
                                StandardCharsets.UTF_8,
                                instanceId,
                                configuration))
                        .thenComposeAsync(ioExecutor, this::snapshotTask);
            }

            Path temporaryArchive = Files.createTempFile("xyml-modpack-update-", ".zip");
            FileDownloadTask downloadTask = new FileDownloadTask(updateSource, temporaryArchive);
            downloadTask.addIntegrityCheckHandler(FileDownloadTask.ZIP_INTEGRITY_CHECK_HANDLER);
            return downloadTask
                    .thenComposeAsync(ioExecutor, ignored -> ModpackHelper.getUpdateTask(
                            repository,
                            temporaryArchive,
                            StandardCharsets.UTF_8,
                            instanceId,
                            configuration))
                    .whenComplete(ioExecutor, failure -> Files.deleteIfExists(temporaryArchive))
                    .thenComposeAsync(ioExecutor, this::snapshotTask);
        });
    }

    /// Uses Core's forced-index asset task and rereads local state on completion.
    ///
    /// @return stopped asset repair and snapshot task
    @Override
    public Task<InstanceMaintenanceSnapshot> redownloadAssets() {
        return Task.composeAsync(ioExecutor, () -> {
            requireExistingInstance();
            Task<?> download = new GameAssetDownloadTask(
                    repository.getDependency(),
                    repository.getResolvedInstanceManifest(instanceId).launchManifest(),
                    GameAssetDownloadTask.DOWNLOAD_INDEX_FORCIBLY,
                    true);
            return download.thenComposeAsync(ioExecutor, this::snapshotTask);
        });
    }

    /// Validates and normalizes one supported remote update source without contacting it.
    ///
    /// @param source requested source
    /// @return normalized absolute HTTP(S) URI
    private static URI requireHttpSource(URI source) {
        URI normalized = Objects.requireNonNull(source, "source").normalize();
        @Nullable String scheme = normalized.getScheme();
        @Nullable String host = normalized.getHost();
        if (scheme == null || host == null || host.isBlank()) {
            throw new IllegalArgumentException("Remote modpack source must be an absolute HTTP(S) URI");
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
            throw new IllegalArgumentException("Remote modpack source must use HTTP or HTTPS");
        }
        return normalized;
    }

    /// Returns whether a source points to the server-modpack manifest form.
    ///
    /// @param source validated remote source
    /// @return whether the source must be parsed as JSON rather than downloaded as an archive
    private static boolean isServerManifestSource(URI source) {
        @Nullable String path = Objects.requireNonNull(source, "source").getPath();
        return path != null && path.toLowerCase(Locale.ROOT).endsWith("/server-manifest.json");
    }

    /// Deletes repository-wide assets plus the fixed instance's legacy resources directory.
    ///
    /// @return stopped cleanup and snapshot task
    @Override
    public Task<InstanceMaintenanceSnapshot> removeAssets() {
        return fileMutationTask(() -> {
            requireExistingInstance();
            FileUtils.deleteDirectory(repository.getBaseDirectory().resolve("assets"));
            FileUtils.deleteDirectory(repository.getRunDirectory(instanceId).resolve("resources"));
        });
    }

    /// Deletes the repository-wide libraries directory.
    ///
    /// @return stopped cleanup and snapshot task
    @Override
    public Task<InstanceMaintenanceSnapshot> removeLibraries() {
        return fileMutationTask(() -> {
            requireExistingInstance();
            FileUtils.deleteDirectory(repository.getBaseDirectory().resolve("libraries"));
        });
    }

    /// Delegates generated log and crash-report cleanup to the repository implementation.
    ///
    /// @return stopped cleanup and snapshot task
    @Override
    public Task<InstanceMaintenanceSnapshot> cleanGeneratedFiles() {
        return fileMutationTask(() -> {
            requireExistingInstance();
            repository.clean(instanceId);
        });
    }

    /// Creates one worker-bound file mutation followed by an authoritative snapshot read.
    ///
    /// @param mutation checked filesystem mutation
    /// @return stopped mutation and snapshot task
    private Task<InstanceMaintenanceSnapshot> fileMutationTask(FileMutation mutation) {
        FileMutation operation = Objects.requireNonNull(mutation, "mutation");
        return Task.runAsync(ioExecutor, operation::run)
                .thenComposeAsync(ioExecutor, this::snapshotTask);
    }

    /// Creates one stopped worker task that reads the latest local state.
    ///
    /// @return stopped snapshot task
    private Task<InstanceMaintenanceSnapshot> snapshotTask() {
        return Task.supplyAsync(ioExecutor, this::readSnapshot);
    }

    /// Reads one immutable snapshot after checking that the fixed instance still exists.
    ///
    /// @return authoritative local maintenance snapshot
    private InstanceMaintenanceSnapshot readSnapshot() {
        requireExistingInstance();
        Path baseDirectory = repository.getBaseDirectory();
        Path runDirectory = repository.getRunDirectory(instanceId);
        return new InstanceMaintenanceSnapshot(
                instanceId,
                repository.isModpack(instanceId),
                Files.exists(baseDirectory.resolve("assets")),
                Files.exists(baseDirectory.resolve("libraries")),
                hasGeneratedFiles(baseDirectory) || hasGeneratedFiles(runDirectory));
    }

    /// Returns whether one game directory contains logs or crash reports.
    ///
    /// @param directory game or run directory
    /// @return whether generated diagnostic data is present
    private static boolean hasGeneratedFiles(Path directory) {
        Path root = Objects.requireNonNull(directory, "directory");
        return Files.exists(root.resolve("logs")) || Files.exists(root.resolve("crash-reports"));
    }

    /// Rejects a stale management page before constructing any destructive operation.
    private void requireExistingInstance() {
        if (!repository.isLoaded() || !repository.hasInstance(instanceId)) {
            throw new IllegalStateException("Unknown instance: " + instanceId);
        }
    }

    /// One checked filesystem mutation executed by a Core task.
    @FunctionalInterface
    @NotNullByDefault
    private interface FileMutation {
        /// Applies the fixed-path mutation.
        ///
        /// @throws IOException when the local operation cannot complete
        void run() throws IOException;
    }
}
