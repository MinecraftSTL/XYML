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
package space.minecraftstl.xyml.modpack;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/// Creates a repository backup, runs one modpack update, and restores the instance when that update fails.
@NotNullByDefault
public class ModpackUpdateTask extends Task<Void> {

    private final DefaultGameRepository repository;
    private final GameInstanceID id;
    private final Task<?> updateTask;
    private final Path backupFolder;

    /// Creates an update task covering the repository backup tree and every explicit child resource.
    ///
    /// @param repository repository containing the instance and backup directory
    /// @param instanceId instance being updated
    /// @param updateTask format-specific update task
    public ModpackUpdateTask(DefaultGameRepository repository, GameInstanceID instanceId, Task<?> updateTask) {
        this.repository = repository;
        this.id = instanceId;
        this.updateTask = updateTask;

        Path backup = repository.getBaseDirectory().resolve("backup");
        while (true) {
            int num = (int)(Math.random() * 10000000);
            if (!Files.exists(backup.resolve(instanceId + "-" + num))) {
                backupFolder = backup.resolve(instanceId + "-" + num);
                break;
            }
        }

        Set<TaskResource> updateResources = updateTask.getResources();
        if (updateResources.equals(Set.of(TaskResource.conservative()))) {
            setResources(TaskResource.gameDirectory(repository.getBaseDirectory()));
        } else {
            setResources(
                    TaskResource.gameDirectory(repository.getBaseDirectory()),
                    updateResources.toArray(TaskResource[]::new));
        }
    }

    @Override
    public Collection<Task<?>> getDependencies() {
        return Collections.singleton(updateTask);
    }

    @Override
    public void execute() throws Exception {
        FileUtils.copyDirectory(repository.getInstanceRoot(id), backupFolder);
    }

    @Override
    public boolean doPostExecute() {
        return true;
    }

    @Override
    public void postExecute() throws Exception {
        if (isDependenciesSucceeded()) {
            // Keep backup game version for further repair.
        } else {
            // Restore backup
            repository.removeInstanceFromDisk(id);

            FileUtils.copyDirectory(backupFolder, repository.getInstanceRoot(id));

            repository.refreshAsync().start();
        }
    }
}
