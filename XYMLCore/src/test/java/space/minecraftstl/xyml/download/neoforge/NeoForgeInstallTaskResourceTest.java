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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.DefaultCacheRepository;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.MojangDownloadProvider;
import space.minecraftstl.xyml.download.forge.ForgeNewInstallTask;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.task.TaskResource;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies NeoForge and processor-based installer tasks declare all repository write trees.
@NotNullByDefault
final class NeoForgeInstallTaskResourceTest {
    /// Temporary repository root used by construction-only resource tests.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies remote and processor-based NeoForge tasks protect instance, shared, and legacy library trees.
    @Test
    void declaresInstanceLibraryAndLegacyLibraryResources() {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory.resolve("repository"));
        DefaultDependencyManager dependencyManager = new DefaultDependencyManager(
                repository,
                new MojangDownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("cache")));
        GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("neoforge-resource-test"));
        @Unmodifiable Set<TaskResource> expected = Set.of(
                TaskResource.gameInstance(repository.getInstanceRoot(manifest.id())),
                TaskResource.gameDirectory(repository.getLibrariesDirectory(manifest)),
                TaskResource.gameDirectory(repository.getBaseDirectory().resolve("lib")));
        Path installer = temporaryDirectory.resolve("neoforge-installer.jar");

        assertEquals(expected, new NeoForgeInstallTask(
                dependencyManager,
                manifest,
                new NeoForgeRemoteVersion(
                        "1.20.1",
                        "20.4.230",
                        List.of("https://example.invalid/neoforge-installer.jar")))
                .getResources());
        @Unmodifiable Set<TaskResource> expectedLocal = Set.of(
                TaskResource.gameInstance(repository.getInstanceRoot(manifest.id())),
                TaskResource.gameDirectory(repository.getLibrariesDirectory(manifest)),
                TaskResource.gameDirectory(repository.getBaseDirectory().resolve("lib")),
                TaskResource.archive(installer));
        assertEquals(expectedLocal, new ForgeNewInstallTask(
                dependencyManager,
                manifest,
                "20.4.230",
                installer).getResources());
        assertEquals(expectedLocal, new NeoForgeOldInstallTask(
                dependencyManager,
                manifest,
                "20.4.230",
                installer).getResources());
    }
}
