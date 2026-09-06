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
package space.minecraftstl.xyml.modpack.multimc;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.DefaultCacheRepository;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.MojangDownloadProvider;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.modpack.Modpack;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies precise MultiMC installation resources and owner-managed recursive component tasks.
@NotNullByDefault
final class MultiMCModpackInstallTaskResourceTest {
    /// Per-test repository, run-directory, archive, and cache roots.
    @TempDir
    private Path temporaryDirectory;

    /// Installation construction snapshots the effective run directory and archive paths.
    ///
    /// @throws IOException if the minimal MultiMC configuration cannot be parsed
    @Test
    void snapshotsInstallationPathsInResourceDeclaration() throws IOException {
        Path repositoryRoot = temporaryDirectory.resolve("repository");
        Path originalRunRoot = temporaryDirectory.resolve("runs");
        MutableRunRepository repository = new MutableRunRepository(repositoryRoot, originalRunRoot);
        GameInstanceID instanceId = new GameInstanceID("example");
        Path archive = temporaryDirectory.resolve("archives/intermediate/../pack.zip");
        Path expectedRun = originalRunRoot.resolve("example").toAbsolutePath().normalize();
        Path expectedArchive = archive.toAbsolutePath().normalize();

        MultiMCModpackInstallTask task = new MultiMCModpackInstallTask(
                dependencyManager(repository),
                archive,
                new TestModpack(),
                configuration(),
                instanceId);
        @Unmodifiable Set<TaskResource> expectedResources = Set.of(
                TaskResource.repositoryOperation(repositoryRoot),
                TaskResource.gameInstance(repository.getInstanceRoot(instanceId)),
                TaskResource.gameDirectory(expectedRun),
                TaskResource.archive(expectedArchive));

        assertEquals(expectedResources, task.getResources());
        repository.setRunRoot(temporaryDirectory.resolve("changed-runs"));
        assertEquals(expectedResources, task.getResources());
    }

    /// A dynamically discovered patch must wait for its resource and complete through the nested executor.
    ///
    /// @throws Exception if asynchronous execution or synchronization fails
    @Test
    void recursivePatchUsesOwnerManagedResourceAcquisition() throws Exception {
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        TaskResource cacheOperation = TaskResource.cacheOperation(cacheDirectory);
        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        CountDownLatch patchCreated = new CountDownLatch(1);
        CountDownLatch patchStarted = new CountDownLatch(1);
        AtomicReference<@Nullable Task<MultiMCInstancePatch>> dynamicTask = new AtomicReference<>();
        AtomicReference<@Nullable String> requestedComponent = new AtomicReference<>();
        AtomicReference<@Nullable String> requestedVersion = new AtomicReference<>();
        AtomicReference<@Nullable String> requestedMinecraftVersion = new AtomicReference<>();
        MultiMCInstancePatch rootPatch = patch("root", "dependency");
        MultiMCInstancePatch dependencyPatch = patch("dependency", null);
        Task<MultiMCInstancePatch> initialTask = Task.supplyAsync(() -> rootPatch).asOrchestration();
        MultiMCModpackInstallTask.MMCInstancePatchesAssembleTask assembler =
                new MultiMCModpackInstallTask.MMCInstancePatchesAssembleTask(
                        List.of(initialTask),
                        "1.20.1",
                        (componentID, version, mcVersion) -> {
                            requestedComponent.set(componentID);
                            requestedVersion.set(version);
                            requestedMinecraftVersion.set(mcVersion);
                            Task<MultiMCInstancePatch> task = Task.supplyAsync(() -> {
                                patchStarted.countDown();
                                return dependencyPatch;
                            }).setResources(cacheOperation);
                            dynamicTask.set(task);
                            patchCreated.countDown();
                            return task;
                        });
        Task<?> holder = Task.runAsync(() -> {
            holderStarted.countDown();
            releaseHolder.await();
        }).setResources(cacheOperation);
        CompletableFuture<Boolean> holderExecution = CompletableFuture.supplyAsync(holder::test);

        try {
            assertTrue(holderStarted.await(5, TimeUnit.SECONDS));
            CompletableFuture<Boolean> assemblyExecution = CompletableFuture.supplyAsync(assembler::test);
            assertTrue(patchCreated.await(5, TimeUnit.SECONDS));
            assertFalse(patchStarted.await(200, TimeUnit.MILLISECONDS));

            releaseHolder.countDown();
            assertTrue(holderExecution.get(5, TimeUnit.SECONDS));
            assertTrue(assemblyExecution.get(5, TimeUnit.SECONDS));
        } finally {
            releaseHolder.countDown();
        }

        assertEquals("dependency", requestedComponent.get());
        assertEquals("1", requestedVersion.get());
        assertEquals("1.20.1", requestedMinecraftVersion.get());
        assertEquals(Task.TaskState.SUCCEEDED, Objects.requireNonNull(dynamicTask.get()).getState());
        assertEquals(List.of("root", "dependency"), Objects.requireNonNull(assembler.getResult()).stream()
                .map(MultiMCInstancePatch::getID)
                .toList());
    }

    /// Creates a minimal decoded component patch with an optional required component.
    ///
    /// @param componentID component identifier
    /// @param requiredComponentID required component identifier, or null for no requirement
    /// @return decoded patch fixture
    private static MultiMCInstancePatch patch(String componentID, @Nullable String requiredComponentID) {
        String requirements = requiredComponentID == null
                ? "[]"
                : "[{\"equals\":\"1\",\"uid\":\"" + requiredComponentID + "\"}]";
        return MultiMCInstancePatch.read(componentID, """
                {
                  "formatVersion": 1,
                  "uid": "%s",
                  "version": "1",
                  "requires": %s
                }
                """.formatted(componentID, requirements));
    }

    /// Creates a dependency manager without performing network access.
    ///
    /// @param repository destination game repository
    /// @return dependency manager for installation construction
    private DefaultDependencyManager dependencyManager(DefaultGameRepository repository) {
        return new DefaultDependencyManager(
                repository,
                new MojangDownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("download-cache")));
    }

    /// Creates the minimal instance configuration required by the installation constructor.
    ///
    /// @return parsed MultiMC configuration
    /// @throws IOException if the fixture cannot be parsed
    private static MultiMCInstanceConfiguration configuration() throws IOException {
        return new MultiMCInstanceConfiguration(
                "Example",
                new ByteArrayInputStream("IntendedVersion=1.20.1\n".getBytes(StandardCharsets.UTF_8)),
                null);
    }

    /// Repository fixture whose run root can change after a task has been constructed.
    @NotNullByDefault
    private static final class MutableRunRepository extends DefaultGameRepository {
        /// Current root used for newly requested run directories.
        private Path runRoot;

        /// Creates a repository with an independently located run root.
        ///
        /// @param repositoryRoot repository base directory
        /// @param runRoot initial run-directory root
        private MutableRunRepository(Path repositoryRoot, Path runRoot) {
            super(repositoryRoot);
            this.runRoot = runRoot;
        }

        /// Returns the current per-instance run directory.
        ///
        /// @param instanceId selected instance
        /// @return current per-instance run directory
        @Override
        public Path getRunDirectory(GameInstanceID instanceId) {
            return runRoot.resolve(instanceId.id());
        }

        /// Changes the run root used by later repository lookups.
        ///
        /// @param runRoot replacement run root
        private void setRunRoot(Path runRoot) {
            this.runRoot = runRoot;
        }
    }

    /// Minimal modpack fixture used only for constructor-level resource assertions.
    @NotNullByDefault
    private static final class TestModpack extends Modpack {
        /// Creates a constructor-only modpack fixture.
        private TestModpack() {
        }

        /// Rejects execution because this fixture is only used to construct the installation task.
        ///
        /// @param dependencyManager repository and download services
        /// @param zipFile input archive
        /// @param instanceId destination instance
        /// @param iconUrl optional icon URL
        /// @return never returns
        @Override
        public Task<?> getInstallTask(
                DefaultDependencyManager dependencyManager,
                Path zipFile,
                GameInstanceID instanceId,
                @Nullable String iconUrl) {
            throw new UnsupportedOperationException("Constructor-only fixture");
        }
    }
}
