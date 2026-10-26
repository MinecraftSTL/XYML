// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.modpack.mcbbs;

import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.GameBuilder;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;
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
import java.util.Optional;

/// Installs a local MCBBS-format archive into one game repository.
@NotNullByDefault
public final class McbbsModpackLocalInstallTask extends Task<Void> {

    private final DefaultDependencyManager dependencyManager;
    private final Path zipFile;
    private final Modpack modpack;
    private final McbbsModpackManifest manifest;
    private final GameInstanceID instanceId;
    private final boolean update;
    private final DefaultGameRepository repository;
    private final MinecraftInstanceTask<McbbsModpackManifest> instanceTask;
    private final List<Task<?>> dependencies = new ArrayList<>(2);
    private final List<Task<?>> dependents = new ArrayList<>(4);

    /// Creates a repository-scoped installation that also owns its input archive.
    ///
    /// @param dependencyManager repository and download services
    /// @param zipFile input modpack archive
    /// @param modpack parsed modpack metadata
    /// @param manifest MCBBS manifest
    /// @param instanceId destination instance
    public McbbsModpackLocalInstallTask(
            DefaultDependencyManager dependencyManager,
            Path zipFile,
            Modpack modpack,
            McbbsModpackManifest manifest,
            GameInstanceID instanceId) {
        this.dependencyManager = dependencyManager;
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
        this.update = repository.hasInstance(instanceId);


        GameBuilder builder = dependencyManager.newGameBuilder().name(instanceId);
        for (McbbsModpackManifest.Addon addon : manifest.getAddons()) {
            builder.version(addon.getId(), addon.getVersion());
        }

        dependents.add(builder.buildAsync());
        onDone().register(event -> {
            if (event.isFailed())
                repository.removeInstanceFromDisk(instanceId);
        });

        @Nullable ModpackConfiguration<McbbsModpackManifest> config = null;
        try {
            if (Files.exists(json)) {
                config = JsonUtils.fromJsonFile(json, ModpackConfiguration.typeOf(McbbsModpackManifest.class));

                if (!McbbsModpackProvider.INSTANCE.getName().equals(config.getType()))
                    throw new IllegalArgumentException("Instance " + instanceId + " is not a Mcbbs modpack. Cannot update this instance.");
            }
        } catch (JsonParseException | IOException ignore) {
        }
        dependents.add(new ModpackInstallTask<>(zipFile, run, modpack.getEncoding(), Collections.singletonList("/overrides"), any -> true, config).withStage("xyml.modpack"));
        instanceTask = new MinecraftInstanceTask<>(zipFile, modpack.getEncoding(), Collections.singletonList("/overrides"), manifest, McbbsModpackProvider.INSTANCE, modpack.getName(), modpack.getVersion(), repository.getModpackConfiguration(instanceId));
        dependents.add(instanceTask.withStage("xyml.modpack"));
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
        GameInstanceManifest instanceManifest = repository.getInstanceManifest(instanceId);
        Optional<GameInstancePatch> mcbbsPatch = instanceManifest.getPatches().stream().filter(patch -> PATCH_NAME.equals(patch.id())).findFirst();
        if (!update) {
            GameInstancePatch patch = new GameInstancePatch(PATCH_NAME).withLibraries(manifest.getLibraries());
            dependencies.add(repository.saveAsync(instanceManifest.addPatch(patch)));
        } else if (mcbbsPatch.isPresent()) {
            // This mcbbs modpack was installed by HMCL.
            GameInstancePatch patch = mcbbsPatch.get().withLibraries(manifest.getLibraries());
            dependencies.add(repository.saveAsync(instanceManifest.addPatch(patch)));
        } else {
            // This mcbbs modpack was installed by other launchers.
            // TODO: maintain libraries.
        }

        dependencies.add(new McbbsModpackCompletionTask(dependencyManager, instanceId, instanceTask.getResult()));
    }

    private static final String PATCH_NAME = "mcbbs";
}
