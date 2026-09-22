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
package space.minecraftstl.xyml.download.neoforge;

import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.download.VersionMismatchException;
import space.minecraftstl.xyml.download.forge.*;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.task.FileDownloadTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.CompressingUtils;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static space.minecraftstl.xyml.util.StringUtils.removePrefix;
import static space.minecraftstl.xyml.util.StringUtils.removeSuffix;

public final class NeoForgeInstallTask extends Task<GameInstancePatch> {
    private final DefaultDependencyManager dependencyManager;

    private final GameInstanceManifest manifest;

    private final NeoForgeRemoteVersion remoteVersion;

    private Path installer = null;

    private FileDownloadTask dependent;

    private Task<GameInstancePatch> dependency;

    /// Completion wrapper that removes a downloaded installer under its exact temporary-file resource.
    private Task<?> completionTask;

    public NeoForgeInstallTask(DefaultDependencyManager dependencyManager, GameInstanceManifest manifest, NeoForgeRemoteVersion remoteVersion) {
        this.dependencyManager = dependencyManager;
        this.manifest = manifest;
        this.remoteVersion = remoteVersion;
        setInstallationResources(this, dependencyManager, manifest, null);
        releaseResourcesBeforeDependencies();
    }

    @Override
    public boolean doPreExecute() {
        return true;
    }

    @Override
    public void preExecute() throws Exception {
        Path stagingDirectory = dependencyManager.getGameRepository()
                .getInstanceRoot(manifest.id())
                .resolve(".xyml-installers");
        Files.createDirectories(stagingDirectory);
        installer = Files.createTempFile(stagingDirectory, "neoforge-installer-", ".jar")
                .toAbsolutePath()
                .normalize();

        dependent = new FileDownloadTask(
                dependencyManager.getDownloadProvider().injectURLsWithCandidates(remoteVersion.getUrls()),
                installer, null
        );
        dependent.setCacheRepository(dependencyManager.getCacheRepository());
        dependent.setCaching(true);
        dependent.addIntegrityCheckHandler(FileDownloadTask.ZIP_INTEGRITY_CHECK_HANDLER);
    }

    @Override
    public boolean doPostExecute() {
        return true;
    }

    @Override
    public void postExecute() throws Exception {
        this.setResult(Objects.requireNonNull(dependency, "installation task").getResult());
    }

    @Override
    public Collection<? extends Task<?>> getDependents() {
        return Collections.singleton(dependent);
    }

    @Override
    public Collection<? extends Task<?>> getDependencies() {
        return completionTask == null
                ? Collections.emptySet()
                : Collections.singleton(completionTask);
    }

    @Override
    public void execute() throws Exception {
        Path installerPath = Objects.requireNonNull(installer, "installer");
        dependency = setInstallationResources(
                install(dependencyManager, manifest, installerPath),
                dependencyManager,
                manifest,
                null);
        completionTask = dependency.whenCompleteWithResources(
                getExecutor(),
                failure -> Files.deleteIfExists(installerPath),
                TaskResource.downloadTarget(installerPath)).asOrchestration();
    }

    public static Task<GameInstancePatch> install(DefaultDependencyManager dependencyManager, GameInstanceManifest version, Path installer) throws IOException, VersionMismatchException {
        Optional<String> gameVersion = dependencyManager.getGameRepository().getGameVersion(version);
        if (!gameVersion.isPresent()) throw new IOException();
        try (FileSystem fs = CompressingUtils.createReadOnlyZipFileSystem(installer)) {
            String installProfileText = Files.readString(fs.getPath("install_profile.json"));
            Map<?, ?> installProfile = JsonUtils.fromNonNullJson(installProfileText, Map.class);
            if (LibraryAnalyzer.LibraryType.FORGE.getPatchId().equals(installProfile.get("profile")) && (Files.exists(fs.getPath("META-INF/NEOFORGE.RSA")) || installProfileText.contains("neoforge"))) {
                ForgeNewInstallProfile profile = JsonUtils.fromNonNullJson(installProfileText, ForgeNewInstallProfile.class);
                if (!gameVersion.get().equals(profile.getMinecraft()))
                    throw new VersionMismatchException(profile.getMinecraft(), gameVersion.get());
                return setInstallationResources(new ForgeNewInstallTask(
                        dependencyManager,
                        version,
                        modifyNeoForgeOldVersion(gameVersion.get(), profile.getVersion()),
                        installer).thenApplyAsync(neoForgeVersion -> {
                    if (!neoForgeVersion.id().equals(LibraryAnalyzer.LibraryType.FORGE.getPatchId()) || neoForgeVersion.version() == null) {
                        throw new IOException("Invalid neoforge version.");
                    }
                    return neoForgeVersion.withId(LibraryAnalyzer.LibraryType.NEO_FORGE.getPatchId())
                            .withVersion(
                                    removePrefix(neoForgeVersion.version().replace(LibraryAnalyzer.LibraryType.FORGE.getPatchId(), ""), "-")
                            );
                }), dependencyManager, version, installer);
            } else if (LibraryAnalyzer.LibraryType.NEO_FORGE.getPatchId().equals(installProfile.get("profile")) || "NeoForge".equals(installProfile.get("profile"))) {
                ForgeNewInstallProfile profile = JsonUtils.fromNonNullJson(installProfileText, ForgeNewInstallProfile.class);
                if (!gameVersion.get().equals(profile.getMinecraft()))
                    throw new VersionMismatchException(profile.getMinecraft(), gameVersion.get());
                return setInstallationResources(
                        new NeoForgeOldInstallTask(
                                dependencyManager,
                                version,
                                modifyNeoForgeNewVersion(profile.getVersion()),
                                installer),
                        dependencyManager,
                        version,
                        installer);
            } else {
                throw new IOException();
            }
        }
    }

    /// Declares repository writes and an optional caller-owned installer archive for one NeoForge task.
    ///
    /// @param task task receiving the immutable declaration
    /// @param dependencyManager repository and download services
    /// @param manifest destination game manifest
    /// @param installerArchive local installer archive, or null for a private downloaded temporary file
    /// @return the supplied task with precise resources
    private static Task<GameInstancePatch> setInstallationResources(
            Task<GameInstancePatch> task,
            DefaultDependencyManager dependencyManager,
            GameInstanceManifest manifest,
            @Nullable Path installerArchive) {
        if (installerArchive == null) {
            return task.setResources(
                    TaskResource.gameInstance(dependencyManager.getGameRepository().getInstanceRoot(manifest.id())),
                    TaskResource.gameDirectory(dependencyManager.getGameRepository().getLibrariesDirectory(manifest)),
                    TaskResource.gameDirectory(dependencyManager.getGameRepository().getBaseDirectory().resolve("lib")));
        }
        return task.setResources(
                TaskResource.gameInstance(dependencyManager.getGameRepository().getInstanceRoot(manifest.id())),
                TaskResource.gameDirectory(dependencyManager.getGameRepository().getLibrariesDirectory(manifest)),
                TaskResource.gameDirectory(dependencyManager.getGameRepository().getBaseDirectory().resolve("lib")),
                TaskResource.archive(installerArchive));
    }

    private static String modifyNeoForgeOldVersion(String gameVersion, String version) {
        return removeSuffix(removePrefix(removeSuffix(removePrefix(version.replace(gameVersion, "").trim(), "-"), "-"), "_"), "_");
    }

    private static String modifyNeoForgeNewVersion(String version) {
        return removePrefix(version.replace("neoforge", ""), "-");
    }
}
