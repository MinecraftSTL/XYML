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
package space.minecraftstl.xyml.download.liteloader;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.DefaultCacheRepository;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.MojangDownloadProvider;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.task.TaskResource;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies LiteLoader roots declare the instance and shared-library resources they mutate.
@NotNullByDefault
final class LiteLoaderInstallTaskResourceTest {
    /// Temporary game repository root used by construction-only task graphs.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies LiteLoader uses its audited instance and shared-library scopes without performing a transfer.
    @Test
    void declaresPreciseInstanceAndLibraryResources() {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory);
        DefaultDependencyManager dependencyManager = new DefaultDependencyManager(
                repository,
                new MojangDownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("cache")));
        GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("liteloader-example"));
        @Unmodifiable Set<TaskResource> expectedResources = Set.of(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(repository.getInstanceRoot(manifest.id())));

        assertEquals(
                expectedResources,
                new LiteLoaderInstallTask(
                        dependencyManager,
                        manifest,
                        new LiteLoaderRemoteVersion(
                                "1.12.2",
                                "1.12.2-SNAPSHOT",
                                RemoteVersion.Type.RELEASE,
                                List.of("https://example.invalid/liteloader.json"),
                                "com.mumfrey.liteloader.launch.LiteLoaderTweaker",
                                List.of()))
                        .getResources());
    }
}
