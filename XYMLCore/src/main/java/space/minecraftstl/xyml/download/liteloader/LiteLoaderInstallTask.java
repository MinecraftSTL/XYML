/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.download.liteloader;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
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
                "http://dl.liteloader.com/versions/",
                new LibrariesDownloadInfo(new LibraryDownloadInfo(null, remote.getUrls().get(0)))
        );

        setResult(new GameInstancePatch(LibraryAnalyzer.LibraryType.LITELOADER.getPatchId(),
                remote.getSelfVersion(),
                60000,
                new Arguments().addGameArguments("--tweakClass", "com.mumfrey.liteloader.launch.LiteLoaderTweaker"),
                LibraryAnalyzer.LAUNCH_WRAPPER_MAIN,
                Lang.merge(remote.getLibraries(), Collections.singleton(library)))
                .withLogging(Collections.emptyMap()) // Mods may log in malformed format, causing XML parser to crash. So we suppress using official log4j configuration
        );

        dependencies.add(new space.minecraftstl.xyml.download.game.GameLibrariesTask(dependencyManager, manifest, true, getResult().getLibraries()));
    }

}
