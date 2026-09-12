/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.DefaultCacheRepository;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.MojangDownloadProvider;
import space.minecraftstl.xyml.download.game.GameAssetDownloadTask;
import space.minecraftstl.xyml.download.game.GameAssetIndexDownloadTask;
import space.minecraftstl.xyml.download.game.GameLibrariesTask;
import space.minecraftstl.xyml.game.AssetIndexInfo;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that shared game-directory tasks hand off broad leases to exact download children.
@NotNullByDefault
final class GameSharedResourceHandoffTest {
    /// Temporary repository root used by each resource declaration fixture.
    @TempDir
    private Path temporaryDirectory;

    /// Asset parsing retains the shared asset lease only until exact object downloads are constructed.
    @Test
    void assetTaskHandsOffBeforeObjectDownloads() {
        DefaultGameRepository repository = repository();
        GameInstanceManifest manifest = manifest().withAssetIndex(
                new AssetIndexInfo("test", "https://example.invalid/assets.json"));

        Task<?> task = new GameAssetDownloadTask(
                dependencyManager(repository), manifest, GameAssetDownloadTask.DOWNLOAD_INDEX_IF_NECESSARY, true);

        assertEquals(
                Set.of(TaskResource.gameDirectory(repository.getAssetDirectory(manifest.id(), "test"))),
                task.getResources());
        assertTrue(task.releasesResourcesBeforeDependencies());
    }

    /// An asset-index task reserves only its exact index file, allowing unrelated asset namespaces to proceed.
    @Test
    void assetIndexTaskDeclaresExactTarget() {
        DefaultGameRepository repository = repository();
        GameInstanceManifest manifest = manifest().withAssetIndex(
                new AssetIndexInfo("test", "https://example.invalid/assets.json"));

        Task<?> task = new GameAssetIndexDownloadTask(dependencyManager(repository), manifest, true);

        assertEquals(
                Set.of(TaskResource.downloadTarget(repository.getIndexFile(manifest.id(), "test"))),
                task.getResources());
    }

    /// Forge's legacy <base>/lib writes remain protected while the library task resolves exact child downloads.
    @Test
    void libraryTaskCoversLegacyForgeDirectoryAndHandsOff() {
        DefaultGameRepository repository = repository();
        GameInstanceManifest manifest = manifest();

        Task<?> task = new GameLibrariesTask(
                dependencyManager(repository), manifest, true, List.of());

        assertEquals(
                Set.of(
                        TaskResource.gameDirectory(repository.getLibrariesDirectory(manifest)),
                        TaskResource.gameDirectory(repository.getBaseDirectory().resolve("lib"))),
                task.getResources());
        assertTrue(task.releasesResourcesBeforeDependencies());
    }

    /// Creates a minimal repository fixture without loading external metadata.
    private DefaultGameRepository repository() {
        return new DefaultGameRepository(temporaryDirectory.resolve("repository"));
    }

    /// Creates a dependency manager backed by the fixture repository.
    private DefaultDependencyManager dependencyManager(DefaultGameRepository repository) {
        return new DefaultDependencyManager(
                repository,
                new MojangDownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("cache")));
    }

    /// Creates a root manifest suitable for resource-only construction tests.
    private GameInstanceManifest manifest() {
        return new GameInstanceManifest(new GameInstanceID("resource-test"));
    }
}
