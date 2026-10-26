// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.modpack.server;

import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.GameBuilder;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.modpack.MinecraftInstanceTask;
import space.minecraftstl.xyml.modpack.Modpack;
import space.minecraftstl.xyml.modpack.ModpackConfiguration;
import space.minecraftstl.xyml.modpack.ModpackInstallTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.gson.JsonUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// Installs a local server-format archive into one game repository.
@NotNullByDefault
public class ServerModpackLocalInstallTask extends Task<Void> {

    private final Path zipFile;
    private final Modpack modpack;
    private final ServerModpackManifest manifest;
    private final GameInstanceID instanceId;
    private final DefaultGameRepository repository;
    private final List<Task<?>> dependencies = new ArrayList<>();
    private final List<Task<?>> dependents = new ArrayList<>(4);

    /// Creates a repository-scoped installation that also owns its input archive.
    ///
    /// @param dependencyManager repository and download services
    /// @param zipFile input modpack archive
    /// @param modpack parsed modpack metadata
    /// @param manifest server-format manifest
    /// @param instanceId destination instance
    public ServerModpackLocalInstallTask(
            DefaultDependencyManager dependencyManager,
            Path zipFile,
            Modpack modpack,
            ServerModpackManifest manifest,
            GameInstanceID instanceId) {
        this.zipFile = zipFile;
        this.modpack = modpack;
        this.manifest = manifest;
        this.instanceId = instanceId;
        this.repository = dependencyManager.getGameRepository();
        Path run = repository.getRunDirectory(instanceId).toAbsolutePath().normalize();
        setResources(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(repository.getInstanceRoot(instanceId)),
                TaskResource.gameDirectory(run),
                TaskResource.archive(zipFile));

        Path json = repository.getModpackConfiguration(instanceId);
        if (repository.hasInstance(instanceId) && Files.notExists(json))
            throw new IllegalArgumentException("Instance " + instanceId + " already exists.");

        GameBuilder builder = dependencyManager.newGameBuilder().name(instanceId);
        for (ServerModpackManifest.Addon addon : manifest.getAddons()) {
            builder.version(addon.getId(), addon.getVersion());
        }

        dependents.add(builder.buildAsync());
        onDone().register(event -> {
            if (event.isFailed())
                repository.removeInstanceFromDisk(instanceId);
        });

        @Nullable ModpackConfiguration<ServerModpackManifest> config = null;
        try {
            if (Files.exists(json)) {
                config = JsonUtils.fromJsonFile(json, ModpackConfiguration.typeOf(ServerModpackManifest.class));

                if (!ServerModpackProvider.INSTANCE.getName().equals(config.getType()))
                    throw new IllegalArgumentException("Instance " + instanceId + " is not a Server modpack. Cannot update this instance.");
            }
        } catch (JsonParseException | IOException ignore) {
        }
        dependents.add(new ModpackInstallTask<>(zipFile, run, modpack.getEncoding(), Collections.singletonList("/overrides"), any -> true, config).withStage("xyml.modpack"));
        dependents.add(new MinecraftInstanceTask<>(zipFile, modpack.getEncoding(), Collections.singletonList("/overrides"), manifest, ServerModpackProvider.INSTANCE, modpack.getName(), modpack.getVersion(), repository.getModpackConfiguration(instanceId)).withStage("xyml.modpack"));
    }

    @Override
    public List<Task<?>> getDependents() {
        return dependents;
    }

    @Override
    public List<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public void execute() throws Exception {
    }
}
