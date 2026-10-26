// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.modpack.mcbbs;

import com.google.gson.JsonParseException;
import kala.compress.archivers.zip.ZipArchiveEntry;
import kala.compress.archivers.zip.ZipArchiveReader;
import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.LaunchOptions;
import space.minecraftstl.xyml.modpack.*;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.gson.JsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

/// Provides parsing, installation, update, and deferred completion for MCBBS-format modpacks.
@NotNullByDefault
public final class McbbsModpackProvider implements ModpackProvider {
    public static final McbbsModpackProvider INSTANCE = new McbbsModpackProvider();

    @Override
    public String getName() {
        return "Mcbbs";
    }

    /// Creates a completion root that retains the selected instance while briefly resolving metadata.
    ///
    /// @param dependencyManager repository and download services
    /// @param instanceId existing destination instance
    /// @return deferred completion task with continuous instance ownership and a short metadata phase
    @Override
    public Task<?> createCompletionTask(DefaultDependencyManager dependencyManager, GameInstanceID instanceId) {
        var repository = dependencyManager.getGameRepository();
        Task<?> resolution = Task.composeAsync(() -> new McbbsModpackCompletionTask(dependencyManager, instanceId)
                        .setResources(
                                TaskResource.gameInstance(repository.getInstanceRoot(instanceId)),
                                TaskResource.gameDirectory(repository.getRunDirectory(instanceId))))
                .setName(McbbsModpackCompletionTask.class.getName())
                .setResources(TaskResource.repositoryMetadata(repository.getBaseDirectory()))
                .releaseResourcesBeforeDependencies();
        return resolution.thenApplyAsync(result -> result)
                .setName(McbbsModpackCompletionTask.class.getName()).setResources(
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
        if (!(modpack.getManifest() instanceof McbbsModpackManifest mcbbsModpackManifest))
            throw new MismatchedModpackTypeException(getName(), modpack.getManifest().getProvider().getName());

        return new ModpackUpdateTask(dependencyManager.getGameRepository(), instanceId, new McbbsModpackLocalInstallTask(dependencyManager, zipFile, modpack, mcbbsModpackManifest, instanceId));
    }

    @Override
    public void injectLaunchOptions(String modpackConfigurationJson, LaunchOptions.Builder builder) {
        ModpackConfiguration<McbbsModpackManifest> config = JsonUtils.GSON.fromJson(modpackConfigurationJson, ModpackConfiguration.typeOf(McbbsModpackManifest.class));

        if (!getName().equals(config.getType())) {
            throw new IllegalArgumentException("Incorrect manifest type, actual=" + config.getType() + ", expected=" + getName());
        }

        config.getManifest().injectLaunchOptions(builder);
    }

    private static Modpack fromManifestFile(InputStream json, Charset encoding) throws IOException, JsonParseException {
        McbbsModpackManifest manifest = JsonUtils.fromNonNullJsonFully(json, McbbsModpackManifest.class);
        return manifest.toModpack(encoding);
    }

    @Override
    public Modpack readManifest(ZipArchiveReader zip, Path file, Charset encoding) throws IOException, JsonParseException {
        ZipArchiveEntry mcbbsPackMeta = zip.getEntry("mcbbs.packmeta");
        if (mcbbsPackMeta != null) {
            return fromManifestFile(zip.getInputStream(mcbbsPackMeta), encoding);
        }
        ZipArchiveEntry manifestJson = zip.getEntry("manifest.json");
        if (manifestJson != null) {
            return fromManifestFile(zip.getInputStream(manifestJson), encoding);
        }
        throw new IOException("`mcbbs.packmeta` or `manifest.json` cannot be found");
    }
}
