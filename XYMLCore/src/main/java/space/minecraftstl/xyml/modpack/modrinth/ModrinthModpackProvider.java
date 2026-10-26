// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.modpack.modrinth;

import com.google.gson.JsonParseException;
import kala.compress.archivers.zip.ZipArchiveReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.repository.ModrinthRemoteAddonRepository;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.modpack.MismatchedModpackTypeException;
import space.minecraftstl.xyml.modpack.Modpack;
import space.minecraftstl.xyml.modpack.ModpackManifest;
import space.minecraftstl.xyml.modpack.ModpackProvider;
import space.minecraftstl.xyml.modpack.ModpackUpdateTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.CompressingUtils;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Set;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Provides parsing, installation, update, and deferred completion for Modrinth-format modpacks.
@NotNullByDefault
public final class ModrinthModpackProvider implements ModpackProvider {
    public static final ModrinthModpackProvider INSTANCE = new ModrinthModpackProvider();

    @Override
    public String getName() {
        return "Modrinth";
    }

    /// Creates a completion root that retains the selected instance while briefly resolving repository metadata.
    ///
    /// @param dependencyManager repository and download services
    /// @param instanceId existing destination instance
    /// @return deferred completion task with continuous instance ownership and a short metadata phase
    @Override
    public Task<?> createCompletionTask(DefaultDependencyManager dependencyManager, GameInstanceID instanceId) {
        var repository = dependencyManager.getGameRepository();
        Task<?> resolution = Task.composeAsync(() -> new ModrinthCompletionTask(dependencyManager, instanceId)
                        .setResources(
                                TaskResource.gameInstance(repository.getInstanceRoot(instanceId)),
                                TaskResource.gameDirectory(repository.getRunDirectory(instanceId))))
                .setName(ModrinthCompletionTask.class.getName())
                .setResources(TaskResource.repositoryMetadata(repository.getBaseDirectory()))
                .releaseResourcesBeforeDependencies();
        return resolution.thenApplyAsync(result -> result)
                .setName(ModrinthCompletionTask.class.getName()).setResources(
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
        if (!(modpack.getManifest() instanceof ModrinthManifest modrinthManifest))
            throw new MismatchedModpackTypeException(getName(), modpack.getManifest().getProvider().getName());

        return new ModpackUpdateTask(
                dependencyManager.getGameRepository(),
                instanceId,
                new ModrinthInstallTask(dependencyManager, zipFile, modpack, modrinthManifest, instanceId, null, excludedFiles));
    }

    @Override
    public Modpack readManifest(ZipArchiveReader zip, Path file, Charset encoding) throws IOException, JsonParseException {
        ModrinthManifest manifest = JsonUtils.fromNonNullJson(CompressingUtils.readTextZipEntry(zip, "modrinth.index.json"), ModrinthManifest.class);
        return new Modpack(manifest.getName(), "", manifest.getVersionId(), manifest.getGameVersion(), manifest.getSummary(), encoding, manifest) {
            @Override
            public Task<?> getInstallTask(
                    DefaultDependencyManager dependencyManager,
                    Path zipFile,
                    GameInstanceID instanceId,
                    String iconUrl,
                    @Nullable Set<String> excludedFiles) {
                return new ModrinthInstallTask(dependencyManager, zipFile, this, manifest, instanceId, iconUrl, excludedFiles);
            }
        };
    }

    @Override
    public ModrinthManifest loadFiles(DownloadProvider downloadProvider, ModpackManifest manifest1) {
        if (!(manifest1 instanceof ModrinthManifest manifest))
            throw new IllegalArgumentException("Manifest is not a ModrinthManifest");
        return manifest.withFiles(manifest.getFiles().parallelStream().map(file -> {
            if (file.optional() && !file.addonQueried()) {
                try {
                    String sha1 = file.hashes().get("sha1");
                    if (sha1 == null) {
                        return file.withAddon(null);
                    }
                    RemoteAddon.Version version = ModrinthRemoteAddonRepository.getInstance()
                            .getRemoteVersionBySHA1(sha1).orElse(null);
                    if (version == null) {
                        return file.withAddon(null);
                    }
                    RemoteAddon addon = ModrinthRemoteAddonRepository.getInstance()
                            .getAddonById(downloadProvider, version.projectId());
                    return file.withAddon(addon);
                } catch (FileNotFoundException fof) {
                    LOG.warning("Could not query Modrinth for deleted mods: " + file.fileName(), fof);
                    return file;
                } catch (IOException | JsonParseException e) {
                    LOG.warning("Unable to fetch the mod id for " + file.fileName(), e);
                    return file;
                }
            }
            return file;
        }).toList());
    }

}
