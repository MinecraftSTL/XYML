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
package space.minecraftstl.xyml.modpack.modrinth;

import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.GameBuilder;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.modpack.*;
import space.minecraftstl.xyml.task.CacheFileTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.io.NetworkUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Installs a downloaded Modrinth-format archive into one game repository.
@NotNullByDefault
public class ModrinthInstallTask extends Task<Void> {

    private final DefaultDependencyManager dependencyManager;
    private final DefaultGameRepository repository;
    private final Path zipFile;
    private final Modpack modpack;
    private final ModrinthManifest manifest;
    private final GameInstanceID instanceId;
    /// Optional remote icon URL.
    private final @Nullable String iconUrl;
    private final Path run;
    /// Existing modpack configuration, or null for a fresh installation.
    private final @Nullable ModpackConfiguration<ModrinthManifest> config;

    /// Validated icon extension, or null when no icon should be installed.
    private @Nullable String iconExt;

    /// Optional icon download task created for a supported remote icon.
    private @Nullable Task<Path> downloadIconTask;
    private final List<Task<?>> dependents = new ArrayList<>(4);
    private final List<Task<?>> dependencies = new ArrayList<>(1);

    /// Creates a repository-scoped installation that also owns its input archive.
    ///
    /// @param dependencyManager repository and download services
    /// @param zipFile input modpack archive
    /// @param modpack parsed modpack metadata
    /// @param manifest Modrinth manifest
    /// @param instanceId destination instance
    /// @param iconUrl optional remote icon URL
    public ModrinthInstallTask(
            DefaultDependencyManager dependencyManager,
            Path zipFile,
            Modpack modpack,
            ModrinthManifest manifest,
            GameInstanceID instanceId,
            @Nullable String iconUrl) {
        this.dependencyManager = dependencyManager;
        this.zipFile = zipFile;
        this.modpack = modpack;
        this.manifest = manifest;
        this.instanceId = instanceId;
        this.iconUrl = iconUrl;
        this.repository = dependencyManager.getGameRepository();
        this.run = repository.getRunDirectory(instanceId).toAbsolutePath().normalize();
        setResources(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(repository.getInstanceRoot(instanceId)),
                TaskResource.gameDirectory(this.run),
                TaskResource.archive(zipFile));

        Path json = repository.getModpackConfiguration(instanceId);
        if (repository.hasInstance(instanceId) && Files.notExists(json))
            throw new IllegalArgumentException("Instance " + instanceId + " already exists.");

        GameBuilder builder = dependencyManager.newGameBuilder().name(instanceId).gameVersion(manifest.getGameVersion());
        for (Map.Entry<String, String> modLoader : manifest.getDependencies().entrySet()) {
            switch (modLoader.getKey()) {
                case "minecraft":
                    break;
                case "forge":
                    builder.version("forge", modLoader.getValue());
                    break;
                case "neoforge":
                // https://github.com/HMCL-dev/HMCL/pull/5170
                case "neo-forge":
                    builder.version("neoforge", modLoader.getValue());
                    break;
                case "fabric-loader":
                    builder.version("fabric", modLoader.getValue());
                    break;
                case "quilt-loader":
                    builder.version("quilt", modLoader.getValue());
                    break;
                default:
                    throw new IllegalStateException("Unsupported mod loader " + modLoader.getKey());
            }
        }
        dependents.add(builder.buildAsync());

        onDone().register(event -> {
            @Nullable Exception ex = event.getTask().getException();
            if (event.isFailed()) {
                if (!(ex instanceof ModpackCompletionException)) {
                    repository.removeInstanceFromDisk(instanceId);
                }
            }
        });

        @Nullable ModpackConfiguration<ModrinthManifest> config = null;
        try {
            if (Files.exists(json)) {
                config = JsonUtils.fromJsonFile(json, ModpackConfiguration.typeOf(ModrinthManifest.class));

                if (!ModrinthModpackProvider.INSTANCE.getName().equals(config.getType()))
                    throw new IllegalArgumentException("Instance " + instanceId + " is not a Modrinth modpack. Cannot update this instance.");
            }
        } catch (JsonParseException | IOException ignore) {
        }

        this.config = config;
        List<String> subDirectories = Arrays.asList("/client-overrides", "/overrides");
        dependents.add(new ModpackInstallTask<>(zipFile, run, modpack.getEncoding(), subDirectories, any -> true, config).withStage("xyml.modpack"));
        dependents.add(new MinecraftInstanceTask<>(zipFile, modpack.getEncoding(), subDirectories, manifest, ModrinthModpackProvider.INSTANCE, manifest.getName(), manifest.getVersionId(), repository.getModpackConfiguration(instanceId)).withStage("xyml.modpack"));

        @Nullable URI iconUri = NetworkUtils.toURIOrNull(iconUrl);
        if (iconUri != null) {
            String ext = FileUtils.getExtension(StringUtils.substringAfter(iconUri.getPath(), '/')).toLowerCase(Locale.ROOT);
            if (Modpack.SUPPORTED_ICON_EXTS.contains(ext)) {
                iconExt = ext;

                dependents.add(downloadIconTask = new CacheFileTask(dependencyManager.getDownloadProvider().injectURLWithCandidates(iconUrl)));
            }
        }
        dependencies.add(new ModrinthCompletionTask(dependencyManager, instanceId, manifest));
    }

    @Override
    public Collection<Task<?>> getDependents() {
        return dependents;
    }

    @Override
    public Collection<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public void execute() throws Exception {
        if (config != null) {
            // For update, remove mods not listed in new manifest
            for (ModrinthManifest.File oldManifestFile : config.getManifest().getFiles()) {
                Path oldFile = run.resolve(oldManifestFile.getPath());
                if (!Files.exists(oldFile)) continue;
                if (manifest.getFiles().stream().noneMatch(oldManifestFile::equals)) {
                    Files.deleteIfExists(oldFile);
                }
            }
        }

        Path root = repository.getInstanceRoot(instanceId);
        Files.createDirectories(root);
        JsonUtils.writeToJsonFile(root.resolve("modrinth.index.json"), manifest);

        if (iconExt != null && Modpack.SUPPORTED_ICON_NAMES.stream().map(root::resolve).allMatch(Files::notExists)) {
            try {
                Files.copy(downloadIconTask.getResult(), root.resolve("icon." + iconExt));
            } catch (Exception e) {
                LOG.warning("Failed to copy modpack icon", e);
            }
        }
    }
}
