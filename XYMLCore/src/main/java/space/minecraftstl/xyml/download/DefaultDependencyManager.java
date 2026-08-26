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
package space.minecraftstl.xyml.download;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.download.cleanroom.CleanroomInstallTask;
import space.minecraftstl.xyml.download.forge.ForgeInstallTask;
import space.minecraftstl.xyml.download.game.GameAssetDownloadTask;
import space.minecraftstl.xyml.download.game.GameDownloadTask;
import space.minecraftstl.xyml.download.game.GameLibrariesTask;
import space.minecraftstl.xyml.download.neoforge.NeoForgeInstallTask;
import space.minecraftstl.xyml.download.optifine.OptiFineInstallTask;
import space.minecraftstl.xyml.game.Artifact;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.Library;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Creates repository-aware installation and repair task graphs.
///
/// @author huangyuhui
@NotNullByDefault
public class DefaultDependencyManager extends AbstractDependencyManager {

    private final DefaultGameRepository repository;
    private final DownloadProvider downloadProvider;
    private final DefaultCacheRepository cacheRepository;

    public DefaultDependencyManager(DefaultGameRepository repository, DownloadProvider downloadProvider, DefaultCacheRepository cacheRepository) {
        this.repository = repository;
        this.downloadProvider = downloadProvider;
        this.cacheRepository = cacheRepository;
    }

    @Override
    public DefaultGameRepository getGameRepository() {
        return repository;
    }

    @Override
    public DownloadProvider getDownloadProvider() {
        return downloadProvider;
    }

    @Override
    public DefaultCacheRepository getCacheRepository() {
        return cacheRepository;
    }

    @Override
    public GameBuilder newGameBuilder() {
        return new DefaultGameBuilder(this);
    }

    /// {@inheritDoc}
    @Override
    public Task<?> checkGameCompletionAsync(GameInstanceManifest manifest, boolean integrityCheck) {
        TaskResource metadataResource = TaskResource.repositoryMetadata(repository.getBaseDirectory());
        TaskResource operationResource = TaskResource.repositoryOperation(repository.getBaseDirectory());
        TaskResource instanceResource = TaskResource.gameInstance(repository.getInstanceRoot(manifest.id()));
        return new Task<>() {
            private List<Task<?>> dependencies = List.of();

            @Override
            public void execute() {
                Path versionJar = repository.getInstanceJar(manifest);
                Task<?> versionAndPatch = Task.composeAsync(() -> {
                    return Files.notExists(versionJar) || FileUtils.size(versionJar) == 0L
                            ? new GameDownloadTask(DefaultDependencyManager.this, null, manifest)
                            : null;
                }).thenComposeAsync(checkPatchCompletionAsync(manifest, integrityCheck))
                        .setResources(operationResource, instanceResource)
                        .releaseResourcesBeforeDependencies();
                dependencies = List.of(
                        versionAndPatch,
                        new GameAssetDownloadTask(
                                DefaultDependencyManager.this,
                                manifest,
                                GameAssetDownloadTask.DOWNLOAD_INDEX_IF_NECESSARY,
                                integrityCheck).setSignificance(Task.TaskSignificance.MODERATE),
                        new GameLibrariesTask(DefaultDependencyManager.this, manifest, integrityCheck));
            }

            @Override
            public List<Task<?>> getDependencies() {
                return dependencies;
            }
        }.setResources(metadataResource, instanceResource)
                .releaseResourcesBeforeDependencies();
    }

    @Override
    public Task<?> checkLibraryCompletionAsync(GameInstanceManifest manifest, boolean integrityCheck) {
        return new GameLibrariesTask(this, manifest, integrityCheck, manifest.getLibraries());
    }

    /// {@inheritDoc}
    @Override
    public Task<?> checkPatchCompletionAsync(GameInstanceManifest manifest, boolean integrityCheck) {
        TaskResource metadataResource = TaskResource.repositoryMetadata(repository.getBaseDirectory());
        TaskResource instanceResource = TaskResource.gameInstance(repository.getInstanceRoot(manifest.id()));
        return new Task<>() {
            private List<Task<?>> dependencies = List.of();

            @Override
            public void execute() throws Exception {
                List<Task<?>> tasks = new ArrayList<>(0);

                @Nullable String gameVersion = repository.getGameVersion(manifest).orElse(null);
                if (gameVersion == null) {
                    dependencies = List.of();
                    return;
                }

                GameInstanceManifest original = repository.getInstanceManifest(manifest.id());
                GameInstanceManifest.Resolved resolvedInstanceManifest = repository.getResolvedInstanceManifest(manifest.id());

                LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(resolvedInstanceManifest, gameVersion);
                for (LibraryAnalyzer.LibraryType type : LibraryAnalyzer.LibraryType.values()) {
                    if (!analyzer.has(type))
                        continue;

                    if (type == LibraryAnalyzer.LibraryType.OPTIFINE) {
                        @Nullable String optifinePatchVersion = analyzer.getVersion(type)
                                .map(optifineVersion -> {
                                    Matcher matcher = Pattern.compile("^([0-9.]+)_(?<optifine>HD_.+)$").matcher(optifineVersion);
                                    return matcher.find() ? matcher.group("optifine") : optifineVersion;
                                })
                                .orElseGet(() -> resolvedInstanceManifest.standaloneManifest().getPatches().stream()
                                        .filter(patch -> "optifine".equals(patch.id()))
                                        .findAny()
                                        .map(gameInstancePatch -> gameInstancePatch.version())
                                        .orElse(null));

                        boolean needsReInstallation = manifest.getLibraries().stream()
                                .anyMatch(library -> !library.hasDownloadURL()
                                        && "optifine".equals(library.groupId())
                                        && GameLibrariesTask.shouldDownloadLibrary(repository, manifest, library, integrityCheck));

                        if (needsReInstallation) {
                            Library installer = new Library(new Artifact(
                                    "optifine",
                                    "OptiFine",
                                    gameVersion + "_" + optifinePatchVersion,
                                    "installer"));
                            if (GameLibrariesTask.shouldDownloadLibrary(repository, manifest, installer, integrityCheck)) {
                                tasks.add(installLibraryAsync(gameVersion, original, "optifine", optifinePatchVersion));
                            } else {
                                tasks.add(OptiFineInstallTask.install(
                                                DefaultDependencyManager.this,
                                                original,
                                                repository.getLibraryFile(manifest, installer))
                                        .setResources(
                                                TaskResource.gameInstance(repository.getInstanceRoot(original.id())),
                                                TaskResource.gameDirectory(repository.getLibrariesDirectory(original))));
                            }
                        }
                    }
                }
                dependencies = List.copyOf(tasks);
            }

            @Override
            public List<Task<?>> getDependencies() {
                return dependencies;
            }
        }.setResources(metadataResource, instanceResource)
                .releaseResourcesBeforeDependencies();
    }

    @Override
    public Task<GameInstanceManifest> installLibraryAsync(String gameVersion, GameInstanceManifest baseVersion, String libraryId, String libraryVersion) {
        VersionList<?> versionList = getVersionList(libraryId);
        return versionList.loadAsync(gameVersion)
                .thenComposeAsync(() -> installLibraryAsync(baseVersion, versionList.getVersion(gameVersion, libraryVersion)
                        .orElseThrow(() -> new IOException("Remote library " + libraryId + " has no version " + libraryVersion))))
                .releaseResourcesBeforeDependencies()
                .withStage(String.format("xyml.install.%s:%s", libraryId, libraryVersion))
                .setResources(
                        TaskResource.gameInstance(repository.getInstanceRoot(baseVersion.id())),
                        TaskResource.gameDirectory(repository.getLibrariesDirectory(baseVersion)));
    }

    @Override
    public Task<GameInstanceManifest> installLibraryAsync(GameInstanceManifest baseVersion, RemoteVersion libraryVersion) {
        AtomicReference<GameInstanceManifest> removedLibraryVersion = new AtomicReference<>();

        return removeLibraryAsync(baseVersion, libraryVersion.getLibraryId())
                .thenComposeAsync(version -> {
                    removedLibraryVersion.set(version);
                    return libraryVersion.getInstallTask(this, version);
                })
                .thenApplyAsync(patch -> {
                    if (patch == null) {
                        return removedLibraryVersion.get();
                    } else {
                        return removedLibraryVersion.get().addPatch(patch);
                    }
                })
                .withStage(String.format("xyml.install.%s:%s", libraryVersion.getLibraryId(), libraryVersion.getSelfVersion()))
                .setResources(
                        TaskResource.gameInstance(repository.getInstanceRoot(baseVersion.id())),
                        TaskResource.gameDirectory(repository.getLibrariesDirectory(baseVersion)));
    }

    public Task<GameInstanceManifest> installLibraryAsync(GameInstanceManifest oldVersion, Path installer) {
        return Task
                .composeAsync(() -> {
                    try {
                        return CleanroomInstallTask.install(this, oldVersion, installer);
                    } catch (IOException ignore) {
                    }

                    try {
                        return NeoForgeInstallTask.install(this, oldVersion, installer);
                    } catch (IOException ignore) {
                    }

                    try {
                        return ForgeInstallTask.install(this, oldVersion, installer);
                    } catch (IOException ignore) {
                    }

                    try {
                        return OptiFineInstallTask.install(this, oldVersion, installer);
                    } catch (IOException ignore) {
                    }

                    throw new UnsupportedLibraryInstallerException();
                })
                .thenApplyAsync(patch -> patch == null ? oldVersion : oldVersion.addPatch(patch))
                .setResources(
                        TaskResource.gameInstance(repository.getInstanceRoot(oldVersion.id())),
                        TaskResource.gameDirectory(repository.getLibrariesDirectory(oldVersion)));
    }

    public static class UnsupportedLibraryInstallerException extends Exception {
    }

    /**
     * Remove installed library.
     * Will try to remove libraries and patches.
     *
     * @param manifest not resolved instance manifest
     * @param libraryId forge/liteloader/optifine/fabric
     * @return task to remove the specified library
     */
    public Task<GameInstanceManifest> removeLibraryAsync(GameInstanceManifest manifest, String libraryId) {
        // MaintainTask requires version that does not inherits from any version.
        // If we want to remove a library in dependent version, we should keep the dependents not changed
        // So resolving this game version to preserve all information in this version.json is necessary.
        return Task.supplyAsync(() -> {
            GameInstanceManifest independentVersion = repository.resolve(manifest).standaloneManifest();
            @Nullable String gameVersion = repository.getGameVersion(independentVersion).orElse(null);
            return LibraryAnalyzer.analyze(independentVersion, gameVersion).removeLibrary(libraryId).build();
        }).setResources(
                TaskResource.gameInstance(repository.getInstanceRoot(manifest.id())),
                TaskResource.gameDirectory(repository.getLibrariesDirectory(manifest)));
    }

}
