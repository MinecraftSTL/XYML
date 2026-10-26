// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.game;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.game.GameInstancePatch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static space.minecraftstl.xyml.game.GameComponentType.GAME;

/// Installs a base game and its shared assets and libraries into one game repository.
@NotNullByDefault
public class GameInstallTask extends Task<GameInstancePatch> {

    private final DefaultGameRepository gameRepository;
    private final DefaultDependencyManager dependencyManager;
    private final GameInstanceManifest manifest;
    private final GameRemoteVersion remote;
    private final GameInstanceJsonDownloadTask downloadTask;
    private final List<Task<?>> dependencies = new ArrayList<>(1);

    /// Creates a repository-scoped base-game installation task.
    ///
    /// @param dependencyManager repository and download services
    /// @param manifest destination instance manifest
    /// @param remoteVersion selected remote base-game version
    public GameInstallTask(
            DefaultDependencyManager dependencyManager,
            GameInstanceManifest manifest,
            GameRemoteVersion remoteVersion) {
        this.dependencyManager = dependencyManager;
        this.gameRepository = dependencyManager.getGameRepository();
        this.manifest = manifest;
        this.remote = remoteVersion;
        TaskResource instanceResource = TaskResource.gameInstance(gameRepository.getInstanceRoot(manifest.id()));
        this.downloadTask = new GameInstanceJsonDownloadTask(remoteVersion.getGameVersion().toString(), dependencyManager);
        this.downloadTask.setResources(instanceResource);
        setResources(
                TaskResource.repositoryOperation(gameRepository.getBaseDirectory()),
                instanceResource);
    }

    @Override
    public Collection<Task<?>> getDependents() {
        return Collections.singleton(downloadTask);
    }

    @Override
    public Collection<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public boolean isRelyingOnDependencies() {
        return false;
    }

    @Override
    public void execute() throws Exception {
        GameInstancePatch patch = GameInstancePatch.fromManifest(
                JsonUtils.fromNonNullJson(downloadTask.getResult(), GameInstanceManifest.class),
                GAME.getPatchId(),
                remote.getGameVersion().toString(),
                GameInstancePatch.PRIORITY_MC).withJar(null);
        setResult(patch);

        GameInstanceManifest version = new GameInstanceManifest(this.manifest.id()).addPatch(patch);
        Task<?> assetsAndLibraries = Task.allOf(
                new GameAssetDownloadTask(dependencyManager, version, GameAssetDownloadTask.DOWNLOAD_INDEX_FORCIBLY, true),
                new GameLibrariesTask(dependencyManager, version, true)
        ).withComposeAsync(Task.runAsync(() -> {
            // Asset and library repair is intentionally optional during base-game installation.
        }).asOrchestration()).asOrchestration();
        Task<?> installation = Task.allOf(
                new GameDownloadTask(dependencyManager, remote.getGameVersion().toString(), version),
                assetsAndLibraries
        ).asOrchestration();
        dependencies.add(installation.thenComposeAsync(gameRepository.saveAsync(version)));
    }

}
