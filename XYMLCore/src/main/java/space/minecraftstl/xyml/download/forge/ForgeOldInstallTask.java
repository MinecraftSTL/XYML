// SPDX-FileCopyrightText: Copyright (C) 2021 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.forge;

import space.minecraftstl.xyml.download.ArtifactMalformedException;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.game.Library;
import space.minecraftstl.xyml.game.GameComponentType;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class ForgeOldInstallTask extends Task<GameInstancePatch> {

    private final DefaultDependencyManager dependencyManager;
    private final GameInstanceManifest manifest;
    private final Path installer;
    private final String selfVersion;
    private final List<Task<?>> dependencies = new ArrayList<>(1);

    ForgeOldInstallTask(DefaultDependencyManager dependencyManager, GameInstanceManifest manifest, String selfVersion, Path installer) {
        this.dependencyManager = dependencyManager;
        this.manifest = manifest;
        this.installer = installer;
        this.selfVersion = selfVersion;

        setResources(
                TaskResource.gameInstance(dependencyManager.getGameRepository().getInstanceRoot(manifest.id())),
                TaskResource.gameDirectory(dependencyManager.getGameRepository().getLibrariesDirectory(manifest)),
                TaskResource.gameDirectory(dependencyManager.getGameRepository().getBaseDirectory().resolve("lib")),
                TaskResource.archive(installer));
        releaseResourcesBeforeDependencies();

        setSignificance(TaskSignificance.MAJOR);
    }

    @Override
    public List<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public boolean doPreExecute() {
        return true;
    }

    @Override
    public void execute() throws Exception {
        try (ZipFile zipFile = new ZipFile(installer.toFile())) {
            InputStream stream = zipFile.getInputStream(zipFile.getEntry("install_profile.json"));
            if (stream == null)
                throw new ArtifactMalformedException("Malformed forge installer file, install_profile.json does not exist.");
            ForgeInstallProfile installProfile = JsonUtils.fromNonNullJsonFully(stream, ForgeInstallProfile.class);

            // unpack the universal jar in the installer file.
            Library forgeLibrary = new Library(installProfile.getInstall().getPath());
            Path forgeFile = dependencyManager.getGameRepository().getLibraryFile(manifest, forgeLibrary);
            Files.createDirectories(forgeFile.getParent());

            ZipEntry forgeEntry = zipFile.getEntry(installProfile.getInstall().getFilePath());
            try (InputStream is = zipFile.getInputStream(forgeEntry);
                 OutputStream os = Files.newOutputStream(forgeFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                is.transferTo(os);
            }

            setResult(GameInstancePatch.fromManifest(
                    installProfile.getVersionInfo(),
                    GameComponentType.FORGE.getPatchId(),
                    selfVersion,
                    GameInstancePatch.PRIORITY_LOADER));
            dependencies.add(dependencyManager.checkLibraryCompletionAsync(installProfile.getVersionInfo(), true));
        } catch (ZipException ex) {
            throw new ArtifactMalformedException("Malformed forge installer file", ex);
        }
    }
}
