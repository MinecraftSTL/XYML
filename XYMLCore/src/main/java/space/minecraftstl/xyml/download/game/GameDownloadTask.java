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
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.task.FileDownloadTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.CacheRepository;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/// Downloads the primary game JAR for one instance.
@NotNullByDefault
public final class GameDownloadTask extends Task<Void> {
    private final DefaultDependencyManager dependencyManager;
    /// Optional canonical game version used to locate a reusable cached JAR.
    private final @Nullable String gameVersion;
    private final GameInstanceManifest manifest;
    private final List<Task<?>> dependencies = new ArrayList<>();

    /// Creates a game-JAR download scoped to the destination instance.
    ///
    /// @param dependencyManager repository and download services
    /// @param gameVersion canonical version, or null when no reusable candidate is known
    /// @param manifest destination instance manifest
    public GameDownloadTask(
            DefaultDependencyManager dependencyManager,
            @Nullable String gameVersion,
            GameInstanceManifest manifest) {
        this.dependencyManager = dependencyManager;
        this.gameVersion = gameVersion;
        this.manifest = manifest.resolve(dependencyManager.getGameRepository());

        setSignificance(TaskSignificance.MODERATE);
        setResources(TaskResource.gameInstance(
                dependencyManager.getGameRepository().getInstanceRoot(this.manifest.id())));
    }

    @Override
    public Collection<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public void execute() {
        Path jar = dependencyManager.getGameRepository().getInstanceJar(manifest);

        var task = new FileDownloadTask(
                dependencyManager.getDownloadProvider().injectURLWithCandidates(manifest.getDownloadInfo().getUrl()),
                jar,
                FileDownloadTask.IntegrityCheck.of(CacheRepository.SHA1, manifest.getDownloadInfo().getSha1()));
        task.setCaching(true);
        task.setCacheRepository(dependencyManager.getCacheRepository());

        if (gameVersion != null)
            task.setCandidate(dependencyManager.getCacheRepository().getCommonDirectory().resolve("jars").resolve(gameVersion + ".jar"));

        dependencies.add(task);
    }
    
}
