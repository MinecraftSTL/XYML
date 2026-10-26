// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.liteloader;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.game.*;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.Lang;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/// Installs LiteLoader metadata and schedules shared-library work for one game instance.
///
/// LiteLoader must be installed after Forge. The repository operation domain lets the generated library task acquire
/// its short shared-directory phase while the precise instance resource keeps same-instance changes ordered.
@NotNullByDefault
public final class LiteLoaderInstallTask extends Task<GameInstancePatch> {

    private final DefaultDependencyManager dependencyManager;
    private final GameInstanceManifest manifest;
    private final LiteLoaderRemoteVersion remote;
    private final List<Task<?>> dependents = new ArrayList<>();
    private final List<Task<?>> dependencies = new ArrayList<>(1);

    /// Creates a repository-operation and instance-scoped LiteLoader installation task.
    ///
    /// @param dependencyManager repository and download services
    /// @param manifest destination game instance manifest
    /// @param remoteVersion selected LiteLoader version
    public LiteLoaderInstallTask(
            DefaultDependencyManager dependencyManager,
            GameInstanceManifest manifest,
            LiteLoaderRemoteVersion remoteVersion) {
        this.dependencyManager = dependencyManager;
        this.manifest = manifest;
        this.remote = remoteVersion;
        DefaultGameRepository gameRepository = dependencyManager.getGameRepository();
        setResources(
                TaskResource.repositoryOperation(gameRepository.getBaseDirectory()),
                TaskResource.gameInstance(gameRepository.getInstanceRoot(manifest.id())));
    }

    @Override
    public Collection<Task<?>> getDependents() {
        return dependents;
    }

    @Override
    public Collection<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public void execute() {
        Library library = new Library(
                new Artifact("com.mumfrey", "liteloader", remote.getSelfVersion()),
                "https://dl.liteloader.com/versions/",
                new LibrariesDownloadInfo(new LibraryDownloadInfo(null, remote.getUrls().get(0)))
        );

        setResult(new GameInstancePatch(GameComponentType.LITELOADER.getPatchId(),
                remote.getSelfVersion(),
                60000,
                new Arguments().addGameArguments("--tweakClass", "com.mumfrey.liteloader.launch.LiteLoaderTweaker"),
                GameComponentAnalyzer.LAUNCH_WRAPPER_MAIN,
                Lang.merge(remote.getLibraries(), Collections.singleton(library)))
                .withLogging(Collections.emptyMap()) // Mods may log in malformed format, causing XML parser to crash. So we suppress using official log4j configuration
        );

        dependencies.add(new space.minecraftstl.xyml.download.game.GameLibrariesTask(dependencyManager, manifest, true, getResult().getLibraries()));
    }

}
