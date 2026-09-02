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
package space.minecraftstl.xyml.download.optifine;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.DefaultCacheRepository;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.MojangDownloadProvider;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.task.TaskResource;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies OptiFine installation resources for remote and local installer paths.
@NotNullByDefault
final class OptiFineInstallTaskResourceTest {
    /// Temporary repository root used by construction-only resource tests.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies remote installs omit private temporary archives while local installs retain the input archive key.
    @Test
    void declaresInstanceLibraryAndOptionalArchiveResources() {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory.resolve("repository"));
        DefaultDependencyManager dependencyManager = new DefaultDependencyManager(
                repository,
                new MojangDownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("cache")));
        GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("optifine-resource-test"));
        OptiFineRemoteVersion remote = new OptiFineRemoteVersion(
                "1.20.1",
                "HD_U_I6",
                Collections.singletonList("https://example.invalid/optifine-installer.jar"),
                false);
        Path installer = temporaryDirectory.resolve("optifine-installer.jar");
        @Unmodifiable Set<TaskResource> expectedRemote = Set.of(
                TaskResource.gameInstance(repository.getInstanceRoot(manifest.id())),
                TaskResource.gameDirectory(repository.getLibrariesDirectory(manifest)));
        @Unmodifiable Set<TaskResource> expectedLocal = Set.of(
                TaskResource.gameInstance(repository.getInstanceRoot(manifest.id())),
                TaskResource.gameDirectory(repository.getLibrariesDirectory(manifest)),
                TaskResource.archive(installer));

        assertEquals(expectedRemote, new OptiFineInstallTask(dependencyManager, manifest, remote).getResources());
        assertEquals(expectedLocal, new OptiFineInstallTask(
                dependencyManager,
                manifest,
                remote,
                installer).getResources());
    }
}
