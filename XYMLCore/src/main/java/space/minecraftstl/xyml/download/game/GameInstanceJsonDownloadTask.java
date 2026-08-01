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
package space.minecraftstl.xyml.download.game;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.download.VersionList;
import space.minecraftstl.xyml.task.GetTask;
import space.minecraftstl.xyml.task.Task;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/// Downloads the official JSON manifest for one real Minecraft version.
@NotNullByDefault
public final class GameInstanceJsonDownloadTask extends Task<String> {
    /// Real Minecraft version identifier requested from the remote catalog.
    private final String gameVersion;

    /// Dependency manager providing the remote catalog and download provider.
    private final DefaultDependencyManager dependencyManager;

    /// Tasks that load the remote version catalog before this task runs.
    private final List<Task<?>> dependents = new ArrayList<>(1);

    /// JSON download tasks scheduled after catalog resolution.
    private final List<Task<?>> dependencies = new ArrayList<>(1);

    /// Remote catalog for real Minecraft versions.
    private final VersionList<?> gameVersionList;

    /// Creates a manifest download task for one real Minecraft version.
    ///
    /// @param gameVersion real Minecraft version identifier
    /// @param dependencyManager dependency manager used for catalog and network access
    public GameInstanceJsonDownloadTask(String gameVersion, DefaultDependencyManager dependencyManager) {
        this.gameVersion = gameVersion;
        this.dependencyManager = dependencyManager;
        this.gameVersionList = dependencyManager.getVersionList("game");

        dependents.add(gameVersionList.loadAsync(gameVersion));

        setSignificance(TaskSignificance.MODERATE);
    }

    /// Returns JSON download work added after catalog resolution.
    @Override
    public Collection<Task<?>> getDependencies() {
        return dependencies;
    }

    /// Returns the prerequisite catalog-loading task.
    @Override
    public Collection<Task<?>> getDependents() {
        return dependents;
    }

    /// Resolves the requested real Minecraft version and schedules its JSON download.
    @Override
    public void execute() throws IOException {
        RemoteVersion remoteVersion = gameVersionList.getVersion(gameVersion, gameVersion)
                .orElseThrow(() -> new IOException("Cannot find specific version " + gameVersion + " in remote repository"));
        dependencies.add(new GetTask(dependencyManager.getDownloadProvider().injectURLsWithCandidates(remoteVersion.getUrls())).storeTo(this::setResult));
    }
}
