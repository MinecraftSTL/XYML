/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.download.cleanroom;

import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.download.UnsupportedInstallationException;
import space.minecraftstl.xyml.download.VersionMismatchException;
import space.minecraftstl.xyml.download.forge.ForgeNewInstallProfile;
import space.minecraftstl.xyml.download.forge.ForgeNewInstallTask;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.task.FileDownloadTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.CompressingUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/// Installs a selected Cleanroom loader patch into an existing game manifest.
@NotNullByDefault
public final class CleanroomInstallTask extends Task<GameInstancePatch> {

    private final DefaultDependencyManager dependencyManager;
    private final GameInstanceManifest manifest;
    private final @Nullable CleanroomRemoteVersion remote;
    private @Nullable Path installer;
    private @Nullable FileDownloadTask dependent;
    private @Nullable Task<GameInstancePatch> task;
    private @Nullable String selfVersion;

    public CleanroomInstallTask(DefaultDependencyManager dependencyManager, GameInstanceManifest manifest, CleanroomRemoteVersion remoteVersion) {
        this.dependencyManager = dependencyManager;
        this.manifest = manifest;
        this.remote = remoteVersion;

        setSignificance(TaskSignificance.MODERATE);
    }

    public CleanroomInstallTask(DefaultDependencyManager dependencyManager, GameInstanceManifest manifest, String selfVersion, Path installer) {
        this.dependencyManager = dependencyManager;
        this.manifest = manifest;
        this.selfVersion = selfVersion;
        this.remote = null;
        this.installer = installer;

        setSignificance(TaskSignificance.MODERATE);
    }

    @Override
    public boolean doPreExecute() {
        return true;
    }

    @Override
    public void preExecute() throws Exception {
        if (installer == null) {
            installer = Files.createTempFile("cleanroom-installer", ".jar");

            dependent = new FileDownloadTask(
                    dependencyManager.getDownloadProvider().injectURLsWithCandidates(remote.getUrls()),
                    installer, null);
            dependent.setCacheRepository(dependencyManager.getCacheRepository());
            dependent.setCaching(true);
            dependent.addIntegrityCheckHandler(FileDownloadTask.ZIP_INTEGRITY_CHECK_HANDLER);
        }
    }

    @Override
    public boolean doPostExecute() {
        return true;
    }

    @Override
    public void postExecute() throws Exception {
        if (remote != null) {
            Files.deleteIfExists(installer);
        }

        setResult(task.getResult());
    }

    @Override
    public Collection<Task<?>> getDependents() {
        return dependent == null ? Collections.emptySet() : Collections.singleton(dependent);
    }

    @Override
    public Collection<Task<?>> getDependencies() {
        return Collections.singleton(task);
    }

    @Override
    public void execute() throws IOException, VersionMismatchException, UnsupportedInstallationException {
        if (selfVersion == null) {
            task = new ForgeNewInstallTask(dependencyManager, manifest, remote.getSelfVersion(), installer).thenApplyAsync((version) -> version.withId(LibraryAnalyzer.LibraryType.CLEANROOM.getPatchId()));
        } else {
            task = new ForgeNewInstallTask(dependencyManager, manifest, selfVersion, installer).thenApplyAsync((version) -> version.withId(LibraryAnalyzer.LibraryType.CLEANROOM.getPatchId()));
        }
    }

    /// Builds a local Cleanroom installation task after validating the target instance.
    ///
    /// @param dependencyManager repository-scoped download services
    /// @param manifest working instance manifest
    /// @param installer local Cleanroom installer
    /// @return task that produces the Cleanroom patch
    /// @throws IOException if the installer or target game version cannot be read
    /// @throws VersionMismatchException if the installer targets another Minecraft version
    /// @throws UnsupportedInstallationException if the target already contains Forge
    public static Task<GameInstancePatch> install(
            DefaultDependencyManager dependencyManager,
            GameInstanceManifest manifest,
            Path installer) throws IOException, VersionMismatchException, UnsupportedInstallationException {
        Optional<String> gameVersion = dependencyManager.getGameRepository().getGameVersion(manifest);
        if (gameVersion.isEmpty()) throw new IOException();
        try (FileSystem fs = CompressingUtils.createReadOnlyZipFileSystem(installer)) {
            String installProfileText = Files.readString(fs.getPath("install_profile.json"));
            Map<?, ?> installProfile = JsonUtils.fromNonNullJson(installProfileText, Map.class);
            if (LibraryAnalyzer.LibraryType.CLEANROOM.getPatchId().equals(installProfile.get("profile"))) {
                checkForgeCompatibility(
                        dependencyManager.getGameRepository().resolve(manifest),
                        gameVersion.get());

                ForgeNewInstallProfile profile = JsonUtils.fromNonNullJson(installProfileText, ForgeNewInstallProfile.class);
                if (!gameVersion.get().equals(profile.getMinecraft()))
                    throw new VersionMismatchException(profile.getMinecraft(), gameVersion.get());
                return new CleanroomInstallTask(dependencyManager, manifest, modifyVersion(profile.getVersion()), installer);
            } else {
                throw new IOException();
            }
        }
    }

    /// Rejects local Cleanroom installation when the target already contains Forge.
    ///
    /// @param resolved resolved target manifest
    /// @param gameVersion resolved Minecraft version
    /// @throws UnsupportedInstallationException if the target already contains Forge
    static void checkForgeCompatibility(
            GameInstanceManifest.Resolved resolved,
            String gameVersion) throws UnsupportedInstallationException {
        LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(resolved, gameVersion);
        if (analyzer.has(LibraryAnalyzer.LibraryType.FORGE)) {
            throw new UnsupportedInstallationException(
                    UnsupportedInstallationException.CLEANROOM_NOT_COMPATIBLE_WITH_FORGE);
        }
    }

    private static String modifyVersion(String version) {
        return version.replace("cleanroom-", "");
    }
}
