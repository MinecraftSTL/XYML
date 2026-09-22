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
package space.minecraftstl.xyml.download.game;

import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNullByDefault;
import org.glavo.url.WebURL;
import space.minecraftstl.xyml.download.AbstractDependencyManager;
import space.minecraftstl.xyml.game.*;
import space.minecraftstl.xyml.task.FileDownloadTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.CacheRepository;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Downloads an instance's asset index and missing objects into the shared game asset repository.
@NotNullByDefault
public final class GameAssetDownloadTask extends Task<Void> {
    
    private final AbstractDependencyManager dependencyManager;
    private final GameInstanceManifest manifest;
    private final AssetIndexInfo assetIndexInfo;
    private final Path assetIndexFile;
    private final boolean integrityCheck;
    private final List<Task<?>> dependents = new ArrayList<>(1);
    private final List<Task<?>> dependencies = new ArrayList<>();

    /// Creates a game-directory-scoped asset download task.
    ///
    /// @param dependencyManager repository and download services
    /// @param manifest game instance manifest
    /// @param forceDownloadingIndex whether the asset index must be refreshed
    /// @param integrityCheck whether existing asset objects must be checksummed
    public GameAssetDownloadTask(
            AbstractDependencyManager dependencyManager,
            GameInstanceManifest manifest,
            boolean forceDownloadingIndex,
            boolean integrityCheck) {
        this.dependencyManager = dependencyManager;
        this.manifest = manifest.resolve(dependencyManager.getGameRepository());
        this.assetIndexInfo = this.manifest.getAssetIndex();
        this.assetIndexFile = dependencyManager.getGameRepository()
                .getIndexFile(this.manifest.id(), assetIndexInfo.getId())
                .toAbsolutePath()
                .normalize();
        this.integrityCheck = integrityCheck;

        setStage("xyml.install.assets");
        setResources(TaskResource.gameDirectory(dependencyManager.getGameRepository().getAssetDirectory(
                this.manifest.id(),
                assetIndexInfo.getId())));
        // Keep the shared asset-directory lease through index parsing and direct cache writes, then let each
        // generated object download acquire its exact target independently.
        releaseResourcesBeforeDependencies();
        dependents.add(new GameAssetIndexDownloadTask(dependencyManager, this.manifest, forceDownloadingIndex));
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
        AssetIndex index;
        try {
            index = JsonUtils.fromNonNullJson(Files.readString(assetIndexFile), AssetIndex.class);
        } catch (IOException | JsonParseException e) {
            throw new GameAssetIndexDownloadTask.GameAssetIndexMalformedException();
        }

        int progress = 0;
        for (AssetObject assetObject : index.getObjects().values()) {
            if (isCancelled())
                throw new InterruptedException();

            Path file = dependencyManager.getGameRepository().getAssetObject(manifest.id(), assetIndexInfo.getId(), assetObject);
            boolean download = !Files.isRegularFile(file);
            try {
                if (!download && integrityCheck && !assetObject.validateChecksum(file, true))
                    download = true;
            } catch (IOException e) {
                LOG.warning("Unable to calc hash value of file " + file, e);
            }
            if (download) {
                @Unmodifiable List<WebURL> urls = dependencyManager.getDownloadProvider().getAssetObjectCandidates(assetObject.getLocation());

                var task = new FileDownloadTask(urls, file, new FileDownloadTask.IntegrityCheck("SHA-1", assetObject.hash()));
                task.setName(assetObject.hash());
                task.setCandidate(dependencyManager.getCacheRepository().getCommonDirectory()
                        .resolve("assets").resolve("objects").resolve(assetObject.getLocation()));
                task.setCacheRepository(dependencyManager.getCacheRepository());
                task.setCaching(true);
                dependencies.add(task.withCounter("xyml.install.assets"));
            } else {
                dependencyManager.getCacheRepository().tryCacheFile(file, CacheRepository.SHA1, assetObject.hash());
            }

            updateProgress(++progress, index.getObjects().size());
        }

        if (!dependencies.isEmpty()) {
            getProperties().put("total", dependencies.size());
            notifyPropertiesChanged();
        }
    }

    public static final boolean DOWNLOAD_INDEX_FORCIBLY = true;
    public static final boolean DOWNLOAD_INDEX_IF_NECESSARY = false;
}
