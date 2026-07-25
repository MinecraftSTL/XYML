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
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.task.Task;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/// Creates a vanilla installation task against one selected repository and download provider.
///
/// Repository refresh and selection are appended on separate caller-selected executors. This keeps
/// blocking scans off UI dispatchers while remaining independent of presentation-toolkit APIs.
@NotNullByDefault
public final class RepositoryVanillaInstallTaskFactory implements GameInstallTaskFactory {
    /// Repository receiving the new instance.
    private final XYMLGameRepository repository;

    /// Provider used to resolve vanilla metadata and artifacts.
    private final DownloadProvider downloadProvider;

    /// Caller-owned background executor used for repository scanning after every outcome.
    private final Executor repositoryRefreshExecutor;

    /// Caller-owned dispatcher used only to publish the selected instance after success.
    private final Executor instanceSelectionExecutor;

    /// Creates a repository-backed vanilla installation factory.
    ///
    /// @param repository selected target repository
    /// @param downloadProvider provider used for this installation
    /// @param repositoryRefreshExecutor caller-owned background executor for repository refresh
    /// @param instanceSelectionExecutor caller-owned dispatcher for selected-instance publication
    public RepositoryVanillaInstallTaskFactory(
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

    /// Validates the destination and creates the complete vanilla install and repository-update chain.
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
        if (!XYMLGameRepository.isValidVersionId(request.instanceName())) {
            throw new GameInstallRequestRejectedException(
                    request,
                    GameInstallRequestRejectedException.Reason.INVALID_INSTANCE_NAME);
        }
        if (repository.versionIdConflicts(request.instanceName())) {
            throw new GameInstallRequestRejectedException(
                    request,
                    GameInstallRequestRejectedException.Reason.INSTANCE_ALREADY_EXISTS);
        }

        GameBuilder builder = repository.getDependency(requestProvider).gameBuilder()
                .name(request.instanceName())
                .gameVersion(request.versionId());
        repository.applyDefaultIsolationSettingForNewInstance(request.instanceName(), false);
        return builder.buildAsync()
                .whenComplete(repositoryRefreshExecutor, ignoredFailure -> repository.refreshVersions())
                .thenRunAsync(
                        instanceSelectionExecutor,
                        () -> repository.setSelectedInstance(request.instanceName()));
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
