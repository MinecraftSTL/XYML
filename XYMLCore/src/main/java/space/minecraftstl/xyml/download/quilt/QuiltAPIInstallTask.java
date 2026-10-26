// SPDX-FileCopyrightText: Copyright (C) 2021 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.quilt;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.task.FileDownloadTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/// Downloads Quilt API into one game instance.
///
/// Quilt itself must be installed first. The API file and dynamic download stay below the instance root.
@NotNullByDefault
public final class QuiltAPIInstallTask extends Task<GameInstancePatch> {

    private final DefaultDependencyManager dependencyManager;
    private final GameInstanceManifest manifest;
    private final QuiltAPIRemoteVersion remote;
    /// Target mods directory resolved by the caller.
    private final Path modsDirectory;
    private final List<Task<?>> dependencies = new ArrayList<>(1);

    /// Creates an instance-scoped installation task with an explicit mods directory.
    ///
    /// @param dependencyManager repository and download services
    /// @param manifest          destination game instance manifest
    /// @param remoteVersion     selected remote version
    /// @param modsDirectory     target mods directory
    public QuiltAPIInstallTask(
            DefaultDependencyManager dependencyManager,
            GameInstanceManifest manifest,
            QuiltAPIRemoteVersion remoteVersion,
            Path modsDirectory) {
        this.dependencyManager = dependencyManager;
        this.manifest = manifest;
        this.remote = remoteVersion;
        this.modsDirectory = modsDirectory;
        setResources(TaskResource.gameInstance(dependencyManager.getGameRepository().getInstanceRoot(manifest.id())));
    }

    /// Creates an instance-scoped installation task that resolves the instance mods directory.
    ///
    /// @param dependencyManager repository and download services
    /// @param manifest          destination game instance manifest
    /// @param remoteVersion     selected remote version
    public QuiltAPIInstallTask(
            DefaultDependencyManager dependencyManager,
            GameInstanceManifest manifest,
            QuiltAPIRemoteVersion remoteVersion) {
        this(dependencyManager, manifest, remoteVersion,
                dependencyManager.getGameRepository().getModsDirectory(manifest.id()));
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
    public void execute() throws IOException {
        dependencies.add(new FileDownloadTask(
                remote.getVersion().file().url(),
                modsDirectory.resolve("quilt-api-" + remote.getVersion().version() + ".jar"),
                remote.getVersion().file().getIntegrityCheck())
        );
    }
}
