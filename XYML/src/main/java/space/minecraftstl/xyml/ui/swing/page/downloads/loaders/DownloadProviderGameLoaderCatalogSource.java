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
package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.DownloadProviderWrapper;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.download.VersionList;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/// Bridges an explicit loader selection to exactly one Core [VersionList] refresh.
///
/// Construction only retains the configured provider. A source request resolves the current concrete
/// provider, calls the selected kind's [VersionList#refreshAsync(String)] once, then snapshots the
/// same list instance while retaining every concrete [RemoteVersion] object.
@NotNullByDefault
public final class DownloadProviderGameLoaderCatalogSource implements GameLoaderCatalogSource {
    /// Configured provider or mutable wrapper resolved only for explicit refresh requests.
    private final DownloadProvider configuredProvider;

    /// Core task launcher retained as an injectable test boundary.
    private final LoaderTaskCompletionRunner taskCompletionRunner;

    /// Creates a source that starts each Core refresh with its normal asynchronous executor.
    ///
    /// @param downloadProvider configured launcher download provider
    public DownloadProviderGameLoaderCatalogSource(DownloadProvider downloadProvider) {
        this(downloadProvider, DownloadProviderGameLoaderCatalogSource::runTask);
    }

    /// Creates a source with an explicit task-completion runner for package-local tests.
    ///
    /// @param downloadProvider configured launcher download provider
    /// @param taskCompletionRunner task launcher and terminal-completion boundary
    DownloadProviderGameLoaderCatalogSource(
            DownloadProvider downloadProvider,
            LoaderTaskCompletionRunner taskCompletionRunner) {
        configuredProvider = Objects.requireNonNull(downloadProvider, "downloadProvider");
        this.taskCompletionRunner = Objects.requireNonNull(taskCompletionRunner, "taskCompletionRunner");
    }

    /// Refreshes the exact requested VersionList and maps its concrete post-refresh versions.
    ///
    /// @param request explicit game-version and loader-kind selection
    /// @return immutable items retaining exact Core RemoteVersion instances
    @Override
    public CompletionStage<@Unmodifiable List<GameLoaderCatalogItem>> refreshAsync(
            GameLoaderCatalogRequest request) {
        GameLoaderCatalogRequest nonNullRequest = Objects.requireNonNull(request, "request");
        final VersionList<?> versionList;
        final Task<?> refreshTask;
        try {
            DownloadProvider provider = unwrapProvider(configuredProvider);
            versionList = Objects.requireNonNull(
                    provider.getVersionListById(nonNullRequest.kind().versionListId()),
                    "download provider returned null version list");
            refreshTask = Objects.requireNonNull(
                    versionList.refreshAsync(nonNullRequest.gameVersion()),
                    "version list returned null refresh task");
        } catch (RuntimeException failure) {
            return failedStage(failure);
        }

        final CompletionStage<Void> terminalStage;
        try {
            terminalStage = Objects.requireNonNull(
                    taskCompletionRunner.run(refreshTask),
                    "task completion runner returned null stage");
        } catch (RuntimeException failure) {
            return failedStage(failure);
        }
        return terminalStage.thenApply(ignored -> mapVersions(versionList, nonNullRequest));
    }

    /// Starts one Core task and turns its terminal listener event into a completion stage.
    ///
    /// @param task Core task returned from the exact VersionList refresh
    /// @return terminal completion stage
    private static CompletionStage<Void> runTask(Task<?> task) {
        TaskExecutor executor = Objects.requireNonNull(task, "task").executor();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        executor.subscribeTaskListener(new TaskListener() {
            /// Completes the source request with the executor's terminal success or failure.
            ///
            /// @param success whether the Core task graph succeeded
            /// @param stoppedExecutor terminal task executor
            @Override
            public void onStop(boolean success, TaskExecutor stoppedExecutor) {
                if (success) {
                    completion.complete(null);
                    return;
                }
                @Nullable Throwable failure = stoppedExecutor.getFailure();
                if (failure != null) {
                    completion.completeExceptionally(failure);
                } else if (stoppedExecutor.isCancelled()) {
                    completion.completeExceptionally(new CancellationException(
                            "Loader catalog refresh was cancelled"));
                } else {
                    completion.completeExceptionally(new IllegalStateException(
                            "Loader catalog refresh stopped without a failure"));
                }
            }
        });
        executor.start();
        return completion.minimalCompletionStage();
    }

    /// Snapshots one exact refreshed VersionList without converting concrete RemoteVersion subtypes.
    ///
    /// @param versionList exact list used to create the refresh task
    /// @param request exact selected loader request
    /// @return immutable mapped items in provider order
    private static @Unmodifiable List<GameLoaderCatalogItem> mapVersions(
            VersionList<?> versionList,
            GameLoaderCatalogRequest request) {
        List<GameLoaderCatalogItem> items = new ArrayList<>();
        for (@Nullable RemoteVersion remoteVersion : versionList.getVersions(request.gameVersion())) {
            if (remoteVersion == null) {
                throw new IllegalStateException("Loader version list returned a null remote version");
            }
            items.add(new GameLoaderCatalogItem(request.kind(), remoteVersion));
        }
        return List.copyOf(items);
    }

    /// Resolves nested mutable provider wrappers to one concrete provider for this refresh request.
    ///
    /// @param provider configured provider or wrapper
    /// @return concrete provider snapshot
    private static DownloadProvider unwrapProvider(DownloadProvider provider) {
        DownloadProvider current = Objects.requireNonNull(provider, "provider");
        Set<DownloadProvider> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current instanceof DownloadProviderWrapper wrapper) {
            if (!visited.add(current)) {
                throw new IllegalStateException("Download-provider wrapper cycle detected");
            }
            current = Objects.requireNonNull(wrapper.getProvider(), "download-provider wrapper provider");
        }
        return current;
    }

    /// Produces one failed immutable item stage without starting a task.
    ///
    /// @param failure source invocation failure
    /// @return failed result stage
    private static CompletionStage<@Unmodifiable List<GameLoaderCatalogItem>> failedStage(
            Throwable failure) {
        CompletableFuture<@Unmodifiable List<GameLoaderCatalogItem>> completion =
                new CompletableFuture<>();
        completion.completeExceptionally(Objects.requireNonNull(failure, "failure"));
        return completion.minimalCompletionStage();
    }
}
