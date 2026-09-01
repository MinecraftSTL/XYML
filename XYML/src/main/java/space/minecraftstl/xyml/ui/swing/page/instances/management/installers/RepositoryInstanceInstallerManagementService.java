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
package space.minecraftstl.xyml.ui.swing.page.instances.management.installers;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.DownloadProviderWrapper;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.DownloadProviders;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.GameLoaderKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/// Adapts XYML's established repository and dependency-manager APIs for existing-instance installer management.
///
/// Every repository scan and task-construction side effect runs outside Swing. Remote installation keeps the
/// exact caller-selected [RemoteVersion] subtypes and caller order, then delegates saving and refresh to the
/// existing Core APIs instead of parsing or writing version JSON directly.
@NotNullByDefault
public final class RepositoryInstanceInstallerManagementService implements InstanceInstallerManagementService {
    /// Repository that owns the target instance metadata and dependency manager.
    private final XYMLGameRepository repository;

    /// Caller-owned executor used for snapshots, validation, and repository refresh operations.
    private final Executor ioExecutor;

    /// Creates a service using XYML's shared I/O scheduler.
    ///
    /// @param repository repository containing target instances
    public RepositoryInstanceInstallerManagementService(XYMLGameRepository repository) {
        this(repository, Schedulers.io());
    }

    /// Creates a service with an explicit caller-owned background executor for deterministic tests.
    ///
    /// @param repository repository containing target instances
    /// @param ioExecutor executor for filesystem-bound service work
    RepositoryInstanceInstallerManagementService(XYMLGameRepository repository, Executor ioExecutor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    /// Reads an immutable installer and third-party-library snapshot on the configured background executor.
    ///
    /// @param instanceId stable target instance identifier
    /// @return asynchronous immutable snapshot
    @Override
    public CompletionStage<InstanceInstallerSnapshot> loadSnapshot(GameInstanceID instanceId) {
        GameInstanceID id = Objects.requireNonNull(instanceId, "instanceId");
        return CompletableFuture.supplyAsync(() -> readSnapshot(id), ioExecutor);
    }

    /// Builds one deferred ordered remote installation chain without re-querying loader metadata.
    ///
    /// @param instanceId stable target instance identifier
    /// @param remoteVersions selected concrete Core versions in desired installation order
    /// @return stopped install-save-refresh-snapshot task
    @Override
    public Task<InstanceInstallerSnapshot> installRemoteVersions(
            GameInstanceID instanceId,
            Collection<? extends RemoteVersion> remoteVersions) {
        GameInstanceID id = Objects.requireNonNull(instanceId, "instanceId");
        @Unmodifiable List<RemoteVersion> capturedVersions = copyRemoteVersions(remoteVersions);
        return Task.composeAsync(ioExecutor, () -> {
            InstanceInstallerSnapshot snapshot = readSnapshot(id);
            @Unmodifiable List<RemoteVersion> validatedVersions =
                    InstanceInstallerCompatibility.validateRemoteInstallation(snapshot, capturedVersions);
            DefaultDependencyManager dependencyManager = repository.getDependency(
                    unwrapProvider(DownloadProviders.getDownloadProvider()));
            Task<GameInstanceManifest> mutation = Task.supplyAsync(
                    ioExecutor,
                    () -> repository.getResolvedInstanceManifest(id).standaloneManifest())
                    .setResources(metadataResource(), instanceResource(id));
            for (RemoteVersion remoteVersion : validatedVersions) {
                mutation = mutation.thenComposeAsync(
                        ioExecutor,
                        manifest -> dependencyManager.installLibraryAsync(manifest, remoteVersion))
                        .asOrchestration();
            }
            return completeMutation(id, mutation);
        }).setResources(metadataResource(), instanceResource(id))
                .releaseResourcesBeforeDependencies();
    }

    /// Builds one deferred removal chain after checking parent-loader safety against the latest snapshot.
    ///
    /// @param instanceId stable target instance identifier
    /// @param libraryId exact Core library identifier
    /// @return stopped removal-save-refresh-snapshot task
    @Override
    public Task<InstanceInstallerSnapshot> removeLibrary(GameInstanceID instanceId, String libraryId) {
        GameInstanceID id = Objects.requireNonNull(instanceId, "instanceId");
        String requestedLibraryId = requireNonBlank(libraryId, "libraryId");
        return Task.composeAsync(ioExecutor, () -> {
            InstanceInstallerSnapshot snapshot = readSnapshot(id);
            InstanceInstallerCompatibility.validateRemoval(snapshot, requestedLibraryId);
            DefaultDependencyManager dependencyManager = repository.getDependency(
                    unwrapProvider(DownloadProviders.getDownloadProvider()));
            Task<GameInstanceManifest> mutation = Task.supplyAsync(
                    ioExecutor,
                    () -> repository.getResolvedInstanceManifest(id).standaloneManifest())
                    .setResources(metadataResource(), instanceResource(id))
                    .thenComposeAsync(
                    ioExecutor,
                    manifest -> dependencyManager.removeLibraryAsync(manifest, requestedLibraryId))
                    .asOrchestration();
            return completeMutation(id, mutation);
        }).setResources(metadataResource(), instanceResource(id))
                .releaseResourcesBeforeDependencies();
    }

    /// Builds one deferred local-installer chain while keeping Core format detection authoritative.
    ///
    /// @param instanceId stable target instance identifier
    /// @param installer selected local installer path
    /// @return stopped offline-install-save-refresh-snapshot task
    @Override
    public Task<InstanceInstallerSnapshot> installOffline(GameInstanceID instanceId, Path installer) {
        GameInstanceID id = Objects.requireNonNull(instanceId, "instanceId");
        Path installerPath = Objects.requireNonNull(installer, "installer").toAbsolutePath().normalize();
        return Task.composeAsync(ioExecutor, () -> {
            DefaultDependencyManager dependencyManager = repository.getDependency(
                    unwrapProvider(DownloadProviders.getDownloadProvider()));
            Task<GameInstanceManifest> mutation = Task.supplyAsync(
                    ioExecutor,
                    () -> {
                        if (!Files.isRegularFile(installerPath)) {
                            throw new InstanceInstallerValidationException(
                                    InstanceInstallerValidationException.Reason.OFFLINE_INSTALLER_NOT_A_REGULAR_FILE,
                                    "Installer path is not a regular file: " + installerPath);
                        }
                        return repository.getResolvedInstanceManifest(id).standaloneManifest();
                    }).setResources(metadataResource(), instanceResource(id))
                    .thenComposeAsync(
                    ioExecutor,
                    manifest -> dependencyManager.installLibraryAsync(manifest, installerPath))
                    .asOrchestration();
            return completeMutation(id, mutation, TaskResource.archive(installerPath));
        }).setResources(metadataResource(), instanceResource(id))
                .releaseResourcesBeforeDependencies();
    }

    /// Saves a mutated manifest, refreshes authoritative repository caches on every outcome, then rereads it.
    ///
    /// @param instanceId stable target identifier
    /// @param mutation stopped Core mutation task returning the changed standalone manifest
    /// @param additionalResources additional immutable inputs retained through the mutation
    /// @return stopped task that returns a fresh snapshot after a successful mutation
    private Task<InstanceInstallerSnapshot> completeMutation(
            GameInstanceID instanceId,
            Task<GameInstanceManifest> mutation,
            TaskResource... additionalResources) {
        List<TaskResource> operationResources = new ArrayList<>();
        operationResources.add(instanceResource(instanceId));
        operationResources.addAll(List.of(Objects.requireNonNull(additionalResources, "additionalResources")));
        Task<GameInstanceManifest> persisted = Objects.requireNonNull(mutation, "mutation")
                .thenComposeAsync(ioExecutor, repository::saveAsync)
                .setResources(
                        TaskResource.repositoryOperation(repository.getBaseDirectory()),
                        operationResources.toArray(TaskResource[]::new));
        Task<@Nullable Void> refreshed = persisted.whenCompleteWithResources(
                ioExecutor,
                ignoredFailure -> repository.refresh(),
                TaskResource.gameDirectory(repository.getBaseDirectory()))
                .asOrchestration();
        return refreshed.thenComposeAsync(
                ioExecutor,
                () -> Task.supplyAsync(ioExecutor, () -> readSnapshot(instanceId))
                        .setResources(metadataResource(), instanceResource(instanceId)))
                .asOrchestration();
    }

    /// Returns the repository metadata scope used while reading or refreshing mutable catalog state.
    ///
    /// @return repository metadata resource
    private TaskResource metadataResource() {
        return TaskResource.repositoryMetadata(repository.getBaseDirectory());
    }

    /// Returns the stable filesystem scope for one existing instance.
    ///
    /// @param instanceId target instance identifier
    /// @return instance resource
    private TaskResource instanceResource(GameInstanceID instanceId) {
        return TaskResource.gameInstance(repository.getInstanceRoot(instanceId));
    }

    /// Reads one instance through Core's analyzer without assuming that detected libraries were XYML-installed.
    ///
    /// @param instanceId stable existing instance identifier
    /// @return immutable recognized-installer and third-party-library snapshot
    private InstanceInstallerSnapshot readSnapshot(GameInstanceID instanceId) {
        GameInstanceManifest independent = repository.getResolvedInstanceManifest(instanceId).standaloneManifest();
        Optional<String> gameVersion = repository.getGameVersion(independent);
        LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(independent, gameVersion.orElse(null));
        List<InstanceInstallerEntry> entries = new ArrayList<>();
        for (GameLoaderKind kind : GameLoaderKind.values()) {
            @Nullable String version = analyzer.getVersion(kind.versionListId()).orElse(null);
            if (version != null) {
                entries.add(new InstanceInstallerEntry(
                        kind,
                        version,
                        analyzer.getLibraryStatus(kind.versionListId())));
            }
        }
        List<InstanceOtherLibraryEntry> otherEntries = new ArrayList<>();
        for (LibraryAnalyzer.LibraryMark mark : analyzer) {
            String libraryId = mark.getLibraryId();
            if ("mcbbs".equals(libraryId)
                    || LibraryAnalyzer.LibraryType.fromPatchId(libraryId) != null) {
                continue;
            }
            otherEntries.add(new InstanceOtherLibraryEntry(
                    libraryId,
                    mark.getLibraryVersion(),
                    InstanceOtherLibraryEntry.StructureState.fromAnalyzerStatus(mark.getStatus())));
        }
        otherEntries.sort(Comparator.comparing(InstanceOtherLibraryEntry::libraryId));
        return new InstanceInstallerSnapshot(instanceId, gameVersion, entries, otherEntries);
    }

    /// Defensively copies a selection while preserving each original remote-version object and order.
    ///
    /// @param remoteVersions caller-owned selected remote versions
    /// @return immutable exact-order object snapshot
    private static @Unmodifiable List<RemoteVersion> copyRemoteVersions(
            Collection<? extends RemoteVersion> remoteVersions) {
        Collection<? extends RemoteVersion> supplied = Objects.requireNonNull(remoteVersions, "remoteVersions");
        List<RemoteVersion> copied = new ArrayList<>(supplied.size());
        for (RemoteVersion remoteVersion : supplied) {
            copied.add(Objects.requireNonNull(remoteVersion, "remoteVersions contains null"));
        }
        return List.copyOf(copied);
    }

    /// Resolves one stable concrete download provider while rejecting mutable-wrapper cycles.
    ///
    /// @param provider configured provider or mutable wrapper
    /// @return concrete provider retained by one mutation task
    private static DownloadProvider unwrapProvider(DownloadProvider provider) {
        DownloadProvider current = Objects.requireNonNull(provider, "provider");
        Set<DownloadProvider> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current instanceof DownloadProviderWrapper wrapper) {
            if (!visited.add(current)) {
                throw new IllegalStateException("Download-provider wrapper cycle detected");
            }
            @Nullable DownloadProvider nestedProvider = wrapper.getProvider();
            current = Objects.requireNonNull(nestedProvider, "download-provider wrapper contains null");
        }
        return current;
    }

    /// Validates one exact non-blank instance or library identifier without normalizing user input.
    ///
    /// @param value candidate identifier
    /// @param name parameter name used in diagnostics
    /// @return exact validated identifier
    private static String requireNonBlank(String value, String name) {
        String candidate = Objects.requireNonNull(value, name);
        if (candidate.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return candidate;
    }
}
