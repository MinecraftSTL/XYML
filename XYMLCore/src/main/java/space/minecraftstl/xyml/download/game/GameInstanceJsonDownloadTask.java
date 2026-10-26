// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.game;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.download.*;
import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.task.GetTask;
import space.minecraftstl.xyml.task.Task;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 *
 * @author huangyuhui
 */
@NotNullByDefault
public final class GameInstanceJsonDownloadTask extends Task<String> {
    private final String gameVersion;
    private final DefaultDependencyManager dependencyManager;
    private final Task<ComponentRemoteVersionList<?>> getGameVersionsTask;
    private final List<Task<?>> dependencies = new ArrayList<>(1);

    public GameInstanceJsonDownloadTask(String gameVersion, DefaultDependencyManager dependencyManager) {
        this.gameVersion = gameVersion;
        this.dependencyManager = dependencyManager;

        getGameVersionsTask = dependencyManager.getDownloadProvider()
                .getVersionsAsync(GameComponentType.GAME, null, false);

        setSignificance(TaskSignificance.MODERATE);
        asOrchestration();
    }

    @Override
    public Collection<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public Collection<Task<?>> getDependents() {
        return List.of(getGameVersionsTask);
    }

    @Override
    public void execute() throws IOException {
        ComponentRemoteVersion remoteVersion = getGameVersionsTask.getResult().getRemoteVersion(gameVersion);
        if (remoteVersion == null)
            throw new IOException(new IOException("Cannot find specific version " + gameVersion + " in remote repository"));
        DownloadProvider downloadProvider = dependencyManager.getDownloadProvider();
        dependencies.add(new GetTask(downloadProvider.getDownloadCandidates(remoteVersion)).storeTo(this::setResult));
    }
}
