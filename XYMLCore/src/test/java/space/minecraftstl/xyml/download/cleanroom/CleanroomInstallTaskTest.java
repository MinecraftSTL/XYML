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
package space.minecraftstl.xyml.download.cleanroom;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.DefaultCacheRepository;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.MojangDownloadProvider;
import space.minecraftstl.xyml.download.UnsupportedInstallationException;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.task.TaskResource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies Cleanroom installation compatibility checks.
@NotNullByDefault
public final class CleanroomInstallTaskTest {
    /// Temporary repository root used by resource declaration tests.
    @TempDir
    private Path temporaryDirectory;

    /// Existing Forge patches reject Cleanroom with the stable compatibility reason.
    @Test
    public void rejectsForgeInstances() {
        UnsupportedInstallationException exception = assertThrows(
                UnsupportedInstallationException.class,
                () -> CleanroomInstallTask.checkForgeCompatibility(resolvedWithPatch("forge"), "1.12.2"));

        assertEquals(
                UnsupportedInstallationException.CLEANROOM_NOT_COMPATIBLE_WITH_FORGE,
                exception.getReason());
    }

    /// Unrelated loader patches do not block Cleanroom installation.
    @Test
    public void acceptsInstancesWithoutForge() {
        assertDoesNotThrow(
                () -> CleanroomInstallTask.checkForgeCompatibility(resolvedWithPatch("fabric"), "1.12.2"));
    }

    /// Verifies remote and local Cleanroom tasks protect the instance, shared libraries, legacy library tree, and archive.
    @Test
    public void declaresInstallationResources() {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory.resolve("repository"));
        DefaultDependencyManager dependencyManager = new DefaultDependencyManager(
                repository,
                new MojangDownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("cache")));
        GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("cleanroom-resource-test"));
        Path installer = temporaryDirectory.resolve("cleanroom-installer.jar");
        @org.jetbrains.annotations.Unmodifiable Set<TaskResource> expectedRemote = Set.of(
                TaskResource.gameInstance(repository.getInstanceRoot(manifest.id())),
                TaskResource.gameDirectory(repository.getLibrariesDirectory(manifest)),
                TaskResource.gameDirectory(repository.getBaseDirectory().resolve("lib")));
        @org.jetbrains.annotations.Unmodifiable Set<TaskResource> expectedLocal = Set.of(
                TaskResource.gameInstance(repository.getInstanceRoot(manifest.id())),
                TaskResource.gameDirectory(repository.getLibrariesDirectory(manifest)),
                TaskResource.gameDirectory(repository.getBaseDirectory().resolve("lib")),
                TaskResource.archive(installer));
        CleanroomRemoteVersion remote = new CleanroomRemoteVersion(
                "1.20.1",
                "0.16.0",
                Instant.EPOCH,
                List.of("https://example.invalid/cleanroom-installer.jar"));

        assertEquals(expectedRemote, new CleanroomInstallTask(dependencyManager, manifest, remote).getResources());
        assertEquals(expectedLocal, new CleanroomInstallTask(
                dependencyManager,
                manifest,
                "0.16.0",
                installer).getResources());
    }

    /// Creates resolved manifest views containing one loader patch.
    ///
    /// @param patchId loader patch identifier
    /// @return resolved fixture
    private static GameInstanceManifest.Resolved resolvedWithPatch(String patchId) {
        GameInstanceManifest launchManifest = new GameInstanceManifest(new GameInstanceID("test"));
        GameInstanceManifest standaloneManifest = launchManifest.withPatches(List.of(new GameInstancePatch(patchId)));
        return new GameInstanceManifest.Resolved(standaloneManifest, launchManifest, standaloneManifest);
    }
}
