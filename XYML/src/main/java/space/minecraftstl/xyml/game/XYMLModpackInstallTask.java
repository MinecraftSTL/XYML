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
package space.minecraftstl.xyml.game;

import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.modpack.MinecraftInstanceTask;
import space.minecraftstl.xyml.modpack.Modpack;
import space.minecraftstl.xyml.modpack.ModpackConfiguration;
import space.minecraftstl.xyml.modpack.ModpackInstallTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.CompressingUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/// Installs or updates an archive in XYML's native modpack format.
@NotNullByDefault
public final class XYMLModpackInstallTask extends Task<Void> {
    /// Source archive to install.
    private final Path zipFile;
    /// Destination game instance identifier.
    private final GameInstanceID instanceId;
    /// Destination repository.
    private final XYMLGameRepository repository;
    /// Repository dependency manager used to install base game files and libraries.
    private final DefaultDependencyManager dependency;
    /// Parsed modpack metadata.
    private final Modpack modpack;
    /// Tasks that must finish before this task executes.
    private final List<Task<?>> dependencies = new ArrayList<>(1);
    /// Tasks that run after this task completes.
    private final List<Task<?>> dependents = new ArrayList<>(4);

    /// Creates a native modpack installation task.
    public XYMLModpackInstallTask(
            XYMLGameRepository repository, Path zipFile, Modpack modpack, GameInstanceID instanceId) {
        this.repository = repository;
        this.dependency = repository.getDependency();
        this.zipFile = zipFile;
        this.instanceId = instanceId;
        this.modpack = modpack;

        Path run = repository.getRunDirectory(this.instanceId);
        Path json = repository.getModpackConfiguration(this.instanceId);
        if (repository.hasInstance(this.instanceId) && Files.notExists(json))
            throw new IllegalArgumentException("Instance " + instanceId + " already exists");

        dependents.add(dependency.newGameBuilder().name(this.instanceId).gameVersion(modpack.getGameVersion()).buildAsync());

        onDone().register(event -> {
            if (event.isFailed()) repository.removeInstanceFromDisk(this.instanceId);
        });

        @Nullable ModpackConfiguration<Modpack> config = null;
        try {
            if (Files.exists(json)) {
                config = JsonUtils.fromJsonFile(json, ModpackConfiguration.typeOf(Modpack.class));

                if (!XYMLModpackProvider.INSTANCE.getName().equals(config.getType()))
                    throw new IllegalArgumentException(
                            "Instance " + instanceId + " is not an XYML modpack. Cannot update this instance.");
            }
        } catch (JsonParseException | IOException ignore) {
        }
        dependents.add(new ModpackInstallTask<>(zipFile, run, modpack.getEncoding(), Collections.singletonList("/minecraft"), it -> !"pack.json".equals(it), config));
        dependents.add(new MinecraftInstanceTask<>(
                zipFile,
                modpack.getEncoding(),
                Collections.singletonList("/minecraft"),
                modpack,
                XYMLModpackProvider.INSTANCE,
                modpack.getName(),
                modpack.getVersion(),
                repository.getModpackConfiguration(this.instanceId)).withStage("xyml.modpack"));
    }

    /// Returns the dynamically assembled prerequisite task list.
    @Override
    public List<Task<?>> getDependencies() {
        return dependencies;
    }

    /// Returns the installation steps scheduled after manifest preparation.
    @Override
    public List<Task<?>> getDependents() {
        return dependents;
    }

    /// Rewrites the bundled instance manifest and reinstalls loader libraries for the destination ID.
    @Override
    public void execute() throws Exception {
        String json = CompressingUtils.readTextZipEntry(zipFile, "minecraft/pack.json");
        GameInstanceManifest parsedManifest = Objects.requireNonNull(
                JsonUtils.GSON.fromJson(json, GameInstanceManifest.class), "Missing minecraft/pack.json manifest");
        GameInstanceManifest originalManifest = parsedManifest.withId(instanceId).withJar(null);
        LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(originalManifest, null);
        Task<GameInstanceManifest> libraryTask = Task.supplyAsync(() -> originalManifest);
        // reinstall libraries
        // libraries of Forge and OptiFine should be obtained by installation.
        for (LibraryAnalyzer.LibraryMark mark : analyzer) {
            if (LibraryAnalyzer.LibraryType.MINECRAFT.getPatchId().equals(mark.getLibraryId()))
                continue;
            libraryTask = libraryTask.thenComposeAsync(version -> dependency.installLibraryAsync(modpack.getGameVersion(), version, mark.getLibraryId(), mark.getLibraryVersion()));
        }

        dependencies.add(libraryTask.thenComposeAsync(repository::saveAsync));
    }
}
