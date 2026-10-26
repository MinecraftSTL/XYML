// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
public final class GameDownloadTask extends Task<Path> {
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
                dependencyManager.getDownloadProvider().getDownloadCandidates(manifest.getDownloadInfo().getUrl()),
                jar,
                FileDownloadTask.IntegrityCheck.of(CacheRepository.SHA1, manifest.getDownloadInfo().getSha1()));
        task.setCaching(true);
        task.setCacheRepository(dependencyManager.getCacheRepository());

        if (gameVersion != null)
            task.setCandidate(dependencyManager.getCacheRepository().getCommonDirectory().resolve("jars").resolve(gameVersion + ".jar"));

        dependencies.add(task);
        setResult(jar);
    }
    
}
