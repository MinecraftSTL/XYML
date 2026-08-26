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
package space.minecraftstl.xyml.download;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.function.ExceptionalFunction;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/// Builds a game instance through sequential base-game, loader, and repository publication stages.
///
/// @author huangyuhui
@NotNullByDefault
public class DefaultGameBuilder extends GameBuilder {

    /// Dependency manager supplying repository paths and concrete install tasks.
    private final DefaultDependencyManager dependencyManager;

    /// Creates a builder backed by one dependency manager.
    ///
    /// @param dependencyManager repository dependency manager
    public DefaultGameBuilder(DefaultDependencyManager dependencyManager) {
        this.dependencyManager = Objects.requireNonNull(dependencyManager, "dependencyManager");
    }

    /// Returns the dependency manager used by this builder.
    ///
    /// @return dependency manager
    public DefaultDependencyManager getDependencyManager() {
        return dependencyManager;
    }

    /// Builds and saves the configured instance while retaining a shared repository operation domain.
    ///
    /// @return stopped game installation task
    @Override
    public Task<?> buildAsync() {
        var hints = new ArrayList<Task.StagesHint>();
        var repository = dependencyManager.getGameRepository();
        GameInstanceID instanceId = Objects.requireNonNull(name, "name");
        TaskResource operationResource = TaskResource.repositoryOperation(repository.getBaseDirectory());
        TaskResource instanceResource = TaskResource.gameInstance(repository.getInstanceRoot(instanceId));

        Task<GameInstanceManifest> libraryTask = Task.supplyAsync(() -> new GameInstanceManifest(instanceId));
        libraryTask = libraryTask.thenComposeAsync(libraryTaskHelper(gameVersion, "game", gameVersion))
                .releaseResourcesBeforeDependencies();
        hints.add(new Task.StagesHint("xyml.install.game:" + gameVersion));
        hints.add(new Task.StagesHint("xyml.install.libraries"));
        hints.add(new Task.StagesHint("xyml.install.assets"));

        for (Map.Entry<String, String> entry : toolVersions.entrySet()) {
            libraryTask = libraryTask.thenComposeAsync(libraryTaskHelper(gameVersion, entry.getKey(), entry.getValue()))
                    .releaseResourcesBeforeDependencies();
            hints.add(new Task.StagesHint(String.format("xyml.install.%s:%s", entry.getKey(), entry.getValue())));
        }

        for (RemoteVersion remoteVersion : remoteVersions) {
            libraryTask = libraryTask.thenComposeAsync(version -> dependencyManager.installLibraryAsync(version, remoteVersion))
                    .releaseResourcesBeforeDependencies();
            hints.add(new Task.StagesHint(String.format("xyml.install.%s:%s", remoteVersion.getLibraryId(), remoteVersion.getSelfVersion())));
        }

        boolean isUpdate = repository.hasInstance(instanceId);

        return libraryTask.thenComposeAsync(repository::saveAsync).releaseResourcesBeforeDependencies().whenComplete(exception -> {
            if (exception != null && !isUpdate) {
                repository.removeInstanceFromDisk(instanceId);
            }
        }).releaseResourcesBeforeDependencies().withStagesHints(hints)
                .setResources(operationResource, instanceResource);
    }

    /// Creates one deferred legacy-library installation function.
    ///
    /// @param gameVersion selected game version
    /// @param libraryId logical library identifier
    /// @param libraryVersion selected library version
    /// @return manifest-to-install-task function
    private ExceptionalFunction<GameInstanceManifest, Task<GameInstanceManifest>, ?> libraryTaskHelper(String gameVersion, String libraryId, String libraryVersion) {
        return version -> dependencyManager.installLibraryAsync(gameVersion, version, libraryId, libraryVersion);
    }
}
