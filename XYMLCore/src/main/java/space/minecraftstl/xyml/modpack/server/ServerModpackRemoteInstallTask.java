// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.modpack.server;

import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.GameBuilder;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.modpack.ModpackConfiguration;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.gson.JsonUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// Installs a remotely described server modpack into one game repository.
@NotNullByDefault
public class ServerModpackRemoteInstallTask extends Task<Void> {

    private final GameInstanceID instanceId;
    private final DefaultDependencyManager dependency;
    private final DefaultGameRepository repository;
    private final List<Task<?>> dependencies = new ArrayList<>(1);
    private final List<Task<?>> dependents = new ArrayList<>(1);
    private final ServerModpackManifest manifest;

    /// Creates a repository-scoped remote installation task.
    ///
    /// @param dependencyManager repository and download services
    /// @param manifest remote server-modpack manifest
    /// @param instanceId destination instance
    public ServerModpackRemoteInstallTask(
            DefaultDependencyManager dependencyManager,
            ServerModpackManifest manifest,
            GameInstanceID instanceId) {
        this.instanceId = instanceId;
        this.dependency = dependencyManager;
        this.repository = dependencyManager.getGameRepository();
        this.manifest = manifest;
        setResources(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(repository.getInstanceRoot(instanceId)),
                TaskResource.gameDirectory(repository.getRunDirectory(instanceId)));

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

        ModpackConfiguration<ServerModpackManifest> config;
        try {
            if (Files.exists(json)) {
                config = JsonUtils.fromJsonFile(json, ModpackConfiguration.typeOf(ServerModpackManifest.class));

                if (!MODPACK_TYPE.equals(config.getType()))
                    throw new IllegalArgumentException("Instance " + instanceId + " is not a Server modpack. Cannot update this instance.");
            }
        } catch (JsonParseException | IOException ignore) {
        }
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
        dependencies.add(new ServerModpackCompletionTask(dependency, instanceId, new ModpackConfiguration<>(manifest, MODPACK_TYPE, manifest.getName(), manifest.getVersion(), Collections.emptyList())));
    }

    public static final String MODPACK_TYPE = "Server";
}
