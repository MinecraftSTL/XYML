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
package space.minecraftstl.xyml.download.forge;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.DefaultCacheRepository;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.MojangDownloadProvider;
import space.minecraftstl.xyml.download.UnsupportedInstallationException;
import space.minecraftstl.xyml.download.forge.ForgeNewInstallProfile.Processor;
import space.minecraftstl.xyml.game.Artifact;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies Forge installation compatibility checks.
@NotNullByDefault
public final class ForgeInstallTaskTest {
    /// Temporary repository root used by resource declaration tests.
    @TempDir
    private Path temporaryDirectory;

    /// Existing Cleanroom patches reject Forge with the stable compatibility reason.
    @Test
    public void rejectsCleanroomInstances() {
        UnsupportedInstallationException exception = assertThrows(
                UnsupportedInstallationException.class,
                () -> ForgeInstallTask.checkCleanroomCompatibility(resolvedWithPatch("cleanroom"), "1.12.2"));

        assertEquals(
                UnsupportedInstallationException.CLEANROOM_NOT_COMPATIBLE_WITH_FORGE,
                exception.getReason());
    }

    /// Unrelated loader patches do not block Forge installation.
    @Test
    public void acceptsInstancesWithoutCleanroom() {
        assertDoesNotThrow(
                () -> ForgeInstallTask.checkCleanroomCompatibility(resolvedWithPatch("fabric"), "1.12.2"));
    }

    /// Verifies Forge installation stages declare every repository tree written by processors and library checks.
    @Test
    public void declaresInstanceLibraryAndLegacyLibraryResources() {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory.resolve("repository"));
        DefaultDependencyManager dependencyManager = new DefaultDependencyManager(
                repository,
                new MojangDownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("cache")));
        GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("forge-resource-test"));
        @Unmodifiable Set<TaskResource> expected = Set.of(
                TaskResource.gameInstance(repository.getInstanceRoot(manifest.id())),
                TaskResource.gameDirectory(repository.getLibrariesDirectory(manifest)),
                TaskResource.gameDirectory(repository.getBaseDirectory().resolve("lib")));
        ForgeRemoteVersion remote = new ForgeRemoteVersion(
                "1.20.1",
                "47.3.0",
                Instant.EPOCH,
                List.of("https://example.invalid/forge-installer.jar"));

        assertEquals(expected, new ForgeInstallTask(dependencyManager, manifest, remote).getResources());
        @Unmodifiable Set<TaskResource> expectedLocal = Set.of(
                TaskResource.gameInstance(repository.getInstanceRoot(manifest.id())),
                TaskResource.gameDirectory(repository.getLibrariesDirectory(manifest)),
                TaskResource.gameDirectory(repository.getBaseDirectory().resolve("lib")),
                TaskResource.archive(temporaryDirectory.resolve("forge-installer.jar")));
        assertEquals(expectedLocal, new ForgeNewInstallTask(
                dependencyManager,
                manifest,
                "47.3.0",
                temporaryDirectory.resolve("forge-installer.jar")).getResources());
        assertEquals(expectedLocal, new ForgeOldInstallTask(
                dependencyManager,
                manifest,
                "47.3.0",
                temporaryDirectory.resolve("forge-installer.jar")).getResources());
    }

    /// Verifies dynamically created Forge processors keep exact installer resources after the outer handoff.
    ///
    /// @throws ReflectiveOperationException if the private processor factory cannot be inspected
    @Test
    public void processorDoesNotFallBackToGlobalResource() throws ReflectiveOperationException {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory.resolve("repository"));
        DefaultDependencyManager dependencyManager = new DefaultDependencyManager(
                repository,
                new MojangDownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("cache")));
        GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("forge-processor-resource-test"));
        ForgeNewInstallTask installation = new ForgeNewInstallTask(
                dependencyManager,
                manifest,
                "47.3.0",
                temporaryDirectory.resolve("forge-installer.jar"));

        Task<?> processorTask = createProcessorTask(installation, List.of());

        assertEquals(installation.getResourceDeclarations(), processorTask.getResourceDeclarations());
    }

    /// Verifies the patched mappings coordinator is resource-free while its download child retains exact resources.
    ///
    /// @throws ReflectiveOperationException if the private processor factory cannot be inspected
    @Test
    public void mappingsPatchUsesOrchestrationResource() throws ReflectiveOperationException {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory.resolve("repository"));
        DefaultDependencyManager dependencyManager = new DefaultDependencyManager(
                repository,
                new MojangDownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("cache")));
        ForgeNewInstallTask installation = new ForgeNewInstallTask(
                dependencyManager,
                new GameInstanceManifest(new GameInstanceID("forge-mappings-resource-test")),
                "47.3.0",
                temporaryDirectory.resolve("forge-installer.jar"));

        Task<?> mappingsTask = createProcessorTask(installation, List.of(
                "--task", "DOWNLOAD_MOJMAPS",
                "--side", "client",
                "--version", "1.20.1",
                "--output", temporaryDirectory.resolve("client-mappings.txt").toString()));

        assertEquals(Set.of(TaskResource.Kind.ORCHESTRATION), mappingsTask.getResources().stream()
                .map(TaskResource::getKind)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    /// Invokes the private dynamic processor factory without starting installer I/O.
    ///
    /// @param installation stopped Forge installer
    /// @param arguments processor arguments
    /// @return stopped dynamically created processor task
    /// @throws ReflectiveOperationException if the private processor factory cannot be invoked
    private static Task<?> createProcessorTask(
            ForgeNewInstallTask installation,
            @Unmodifiable List<String> arguments) throws ReflectiveOperationException {
        Method factory = ForgeNewInstallTask.class.getDeclaredMethod(
                "createProcessorTask",
                Processor.class,
                Map.class);
        factory.setAccessible(true);
        Processor processor = new Processor(
                List.of("client"),
                new Artifact("example", "processor", "1.0"),
                List.of(),
                arguments,
                Map.of());
        return (Task<?>) factory.invoke(installation, processor, Map.of());
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
