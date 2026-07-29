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
package space.minecraftstl.xyml.game.install;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.DownloadProviderWrapper;
import space.minecraftstl.xyml.download.GameBuilder;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.task.Task;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/// Creates a complete game-installation task against one selected repository and download provider.
///
/// The request may be vanilla-only or contain an ordered set of remote loader installers. Repository
/// refresh and selected-instance publication are appended on caller-selected executors so the task
/// remains independent of presentation-toolkit APIs.
@NotNullByDefault
public final class RepositoryGameInstallTaskFactory implements GameInstallTaskFactory {
    /// Repository receiving the new instance.
    private final XYMLGameRepository repository;

    /// Provider used to resolve base-game metadata and optional loader artifacts.
    private final DownloadProvider downloadProvider;

    /// Caller-owned background executor used for repository scanning after every outcome.
    private final Executor repositoryRefreshExecutor;

    /// Caller-owned dispatcher used only to publish the selected instance after success.
    private final Executor instanceSelectionExecutor;

    /// Creates a repository-backed game-installation factory.
    ///
    /// @param repository selected target repository
    /// @param downloadProvider provider used for this installation
    /// @param repositoryRefreshExecutor caller-owned background executor for repository refresh
    /// @param instanceSelectionExecutor caller-owned dispatcher for selected-instance publication
    public RepositoryGameInstallTaskFactory(
            XYMLGameRepository repository,
            DownloadProvider downloadProvider,
            Executor repositoryRefreshExecutor,
            Executor instanceSelectionExecutor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.downloadProvider = Objects.requireNonNull(downloadProvider, "downloadProvider");
        this.repositoryRefreshExecutor = Objects.requireNonNull(
                repositoryRefreshExecutor,
                "repositoryRefreshExecutor");
        this.instanceSelectionExecutor = Objects.requireNonNull(
                instanceSelectionExecutor,
                "instanceSelectionExecutor");
    }

    /// Validates the destination and creates the complete install and repository-update chain.
    ///
    /// @param request immutable game-installation request
    /// @return stopped task that installs the requested game and any selected loaders
    @Override
    public Task<?> create(GameInstallRequest request) {
        Objects.requireNonNull(request, "request");
        return Task.composeAsync(() -> createDeferredInstallTask(
                request,
                unwrapProvider(downloadProvider)));
    }

    /// Performs destination validation and side-effectful task construction only after execution starts.
    ///
    /// @param request captured installation request
    /// @param requestProvider concrete provider snapshotted for this request
    /// @return complete install, refresh, and selection task chain
    private Task<?> createDeferredInstallTask(
            GameInstallRequest request,
            DownloadProvider requestProvider) {
        if (!XYMLGameRepository.isValidInstanceId(request.instanceName())) {
            throw new GameInstallRequestRejectedException(
                    request,
                    GameInstallRequestRejectedException.Reason.INVALID_INSTANCE_NAME);
        }
        GameInstanceID instanceId = new GameInstanceID(request.instanceName());
        if (repository.instanceIdConflicts(instanceId)) {
            throw new GameInstallRequestRejectedException(
                    request,
                    GameInstallRequestRejectedException.Reason.INSTANCE_ALREADY_EXISTS);
        }

        GameBuilder builder = repository.getDependency(requestProvider).newGameBuilder();
        configureBuilder(builder, instanceId, request);
        repository.applyDefaultIsolationSettingForNewInstance(instanceId, isModded(request));
        return builder.buildAsync()
                .whenComplete(repositoryRefreshExecutor, ignoredFailure -> repository.refresh())
                .thenRunAsync(
                        instanceSelectionExecutor,
                        () -> repository.setSelectedInstance(instanceId));
    }

    /// Applies the request's base game and remote installers to a newly created game builder.
    ///
    /// Selected remote installers are passed to [GameBuilder#version(RemoteVersion)] in exact request
    /// order. This is deliberately separate from construction so tests can verify task composition
    /// without opening a repository or starting any download.
    ///
    /// @param builder fresh builder obtained from the selected repository dependency manager
    /// @param instanceId validated destination instance identifier
    /// @param request immutable request containing the base game and selected installers
    static void configureBuilder(
            GameBuilder builder,
            GameInstanceID instanceId,
            GameInstallRequest request) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(request, "request");
        builder.name(instanceId).gameVersion(request.versionId());
        for (RemoteVersion remoteVersion : request.selectedRemoteVersions()) {
            builder.version(remoteVersion);
        }
    }

    /// Determines whether the selected remote installers contain a real mod loader.
    ///
    /// The repository isolation rule considers only [LibraryAnalyzer.LibraryType#isModLoader()] as
    /// considered modded, so auxiliary components such as OptiFine alone do not change the default
    /// running-directory policy.
    ///
    /// @param request immutable installation request
    /// @return whether default isolation should use the modded branch
    static boolean isModded(GameInstallRequest request) {
        Objects.requireNonNull(request, "request");
        for (RemoteVersion remoteVersion : request.selectedRemoteVersions()) {
            @Nullable LibraryAnalyzer.LibraryType type = LibraryAnalyzer.LibraryType.fromPatchId(
                    remoteVersion.getLibraryId());
            if (type != null && type.isModLoader()) {
                return true;
            }
        }
        return false;
    }

    /// Resolves a stable concrete provider snapshot while rejecting wrapper cycles and null links.
    ///
    /// @param provider configured provider or mutable wrapper
    /// @return concrete provider used throughout one installation task
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
}
