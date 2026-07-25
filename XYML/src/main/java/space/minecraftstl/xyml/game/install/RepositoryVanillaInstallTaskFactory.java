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
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.task.Task;

import java.util.Objects;
import java.util.concurrent.Executor;

/// Compatibility facade for callers that still use the former vanilla-only factory name.
///
/// New code should use [RepositoryGameInstallTaskFactory], which supports both vanilla and selected
/// remote loader installers. This facade preserves the original constructor and delegates without
/// changing task behavior.
@Deprecated
@NotNullByDefault
public final class RepositoryVanillaInstallTaskFactory implements GameInstallTaskFactory {
    /// General-purpose implementation receiving all installation requests.
    private final RepositoryGameInstallTaskFactory delegate;

    /// Creates a compatibility facade around the general repository-backed factory.
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
        delegate = new RepositoryGameInstallTaskFactory(
                Objects.requireNonNull(repository, "repository"),
                Objects.requireNonNull(downloadProvider, "downloadProvider"),
                Objects.requireNonNull(repositoryRefreshExecutor, "repositoryRefreshExecutor"),
                Objects.requireNonNull(instanceSelectionExecutor, "instanceSelectionExecutor"));
    }

    /// Creates the stopped installation task through the general-purpose implementation.
    ///
    /// @param request immutable game-installation request
    /// @return stopped task whose success includes repository post-processing
    @Override
    public Task<?> create(GameInstallRequest request) {
        return delegate.create(Objects.requireNonNull(request, "request"));
    }
}
