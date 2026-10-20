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
package space.minecraftstl.xyml.modpack.server;

import com.google.gson.JsonParseException;
import kala.compress.archivers.zip.ZipArchiveReader;
import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.modpack.MismatchedModpackTypeException;
import space.minecraftstl.xyml.modpack.Modpack;
import space.minecraftstl.xyml.modpack.ModpackProvider;
import space.minecraftstl.xyml.modpack.ModpackUpdateTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.CompressingUtils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

/// Provides parsing, installation, update, and deferred completion for server-format modpacks.
@NotNullByDefault
public final class ServerModpackProvider implements ModpackProvider {
    public static final ServerModpackProvider INSTANCE = new ServerModpackProvider();

    @Override
    public String getName() {
        return "Server";
    }

    /// Creates a completion root that retains repository and instance ownership during metadata resolution.
    ///
    /// @param dependencyManager repository and download services
    /// @param instanceId existing destination instance
    /// @return deferred completion task with continuous operation and instance ownership and a short metadata phase
    @Override
    public Task<?> createCompletionTask(DefaultDependencyManager dependencyManager, GameInstanceID instanceId) {
        var repository = dependencyManager.getGameRepository();
        Task<?> resolution = Task.composeAsync(() -> new ServerModpackCompletionTask(dependencyManager, instanceId)
                        .setResources(
                                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                                TaskResource.gameInstance(repository.getInstanceRoot(instanceId)),
                                TaskResource.gameDirectory(repository.getRunDirectory(instanceId))))
                .setName(ServerModpackCompletionTask.class.getName())
                .setResources(TaskResource.repositoryMetadata(repository.getBaseDirectory()))
                .releaseResourcesBeforeDependencies();
        return resolution.thenApplyAsync(result -> result)
                .setName(ServerModpackCompletionTask.class.getName()).setResources(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(repository.getInstanceRoot(instanceId)),
                TaskResource.gameDirectory(repository.getRunDirectory(instanceId)));
    }

    @Override
    public Task<?> createUpdateTask(
            DefaultDependencyManager dependencyManager,
            GameInstanceID instanceId,
            Path zipFile,
            Modpack modpack,
            @Nullable Set<String> excludedFiles) throws MismatchedModpackTypeException {
        if (!(modpack.getManifest() instanceof ServerModpackManifest serverModpackManifest))
            throw new MismatchedModpackTypeException(getName(), modpack.getManifest().getProvider().getName());

        return new ModpackUpdateTask(dependencyManager.getGameRepository(), instanceId, new ServerModpackLocalInstallTask(dependencyManager, zipFile, modpack, serverModpackManifest, instanceId));
    }

    @Override
    public Modpack readManifest(ZipArchiveReader zip, Path file, Charset encoding) throws IOException, JsonParseException {
        String json = CompressingUtils.readTextZipEntry(zip, "server-manifest.json");
        ServerModpackManifest manifest = JsonUtils.fromNonNullJson(json, ServerModpackManifest.class);
        return manifest.toModpack(encoding);
    }
}
