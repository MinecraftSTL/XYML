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
package space.minecraftstl.xyml.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.addon.mod.ModManager;
import space.minecraftstl.xyml.download.DefaultCacheRepository;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.MojangDownloadProvider;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.modpack.ModpackProvider;
import space.minecraftstl.xyml.modpack.curse.CurseModpackProvider;
import space.minecraftstl.xyml.modpack.mcbbs.McbbsModpackProvider;
import space.minecraftstl.xyml.modpack.modrinth.ModrinthModpackProvider;
import space.minecraftstl.xyml.modpack.server.ServerModpackProvider;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies deferred repository-metadata resolution for independently started modpack completion tasks.
@NotNullByDefault
final class ModpackProviderResourceTest {
    /// Per-test repository, run-directory, and cache roots.
    @TempDir
    private Path temporaryDirectory;

    /// Providers retain operation resources outside a short metadata resolver and hand precise resources to its child.
    ///
    /// @throws Exception if deferred child construction fails
    @Test
    void completionRootsHandOffToIsolatedInstanceResources() throws Exception {
        Path repositoryRoot = temporaryDirectory.resolve("repository");
        Path runRoot = temporaryDirectory.resolve("runs");
        TrackingRunRepository repository = new TrackingRunRepository(repositoryRoot, runRoot);
        DefaultDependencyManager dependencyManager = dependencyManager(repository);
        GameInstanceID instanceId = new GameInstanceID("example");
        @Unmodifiable Set<TaskResource> expectedChildResources = Set.of(
                TaskResource.gameInstance(repositoryRoot.resolve("versions/example")),
                TaskResource.gameDirectory(runRoot.resolve("example")));
        TaskResource operationResource = TaskResource.repositoryOperation(repositoryRoot);

        @Unmodifiable Set<TaskResource> expectedRootResources = Set.of(
                operationResource,
                TaskResource.gameInstance(repositoryRoot.resolve("versions/example")),
                TaskResource.gameDirectory(runRoot.resolve("example")));
        assertCompletionHandoff(
                CurseModpackProvider.INSTANCE,
                dependencyManager,
                instanceId,
                repository,
                repositoryRoot,
                expectedRootResources,
                expectedChildResources);
        assertCompletionHandoff(
                ModrinthModpackProvider.INSTANCE,
                dependencyManager,
                instanceId,
                repository,
                repositoryRoot,
                expectedRootResources,
                expectedChildResources);
        assertCompletionHandoff(
                McbbsModpackProvider.INSTANCE,
                dependencyManager,
                instanceId,
                repository,
                repositoryRoot,
                Set.of(
                        operationResource,
                        TaskResource.gameInstance(repositoryRoot.resolve("versions/example")),
                        TaskResource.gameDirectory(runRoot.resolve("example"))),
                Set.of(
                        TaskResource.gameInstance(repositoryRoot.resolve("versions/example")),
                        TaskResource.gameDirectory(runRoot.resolve("example"))));
        assertCompletionHandoff(
                ServerModpackProvider.INSTANCE,
                dependencyManager,
                instanceId,
                repository,
                repositoryRoot,
                Set.of(
                        operationResource,
                        TaskResource.gameInstance(repositoryRoot.resolve("versions/example")),
                        TaskResource.gameDirectory(runRoot.resolve("example"))),
                Set.of(
                        TaskResource.gameInstance(repositoryRoot.resolve("versions/example")),
                        TaskResource.gameDirectory(runRoot.resolve("example")),
                        operationResource));
    }

    /// A completion child retains the repository-wide boundary when the effective run directory is shared.
    ///
    /// @throws Exception if deferred child construction fails
    @Test
    void completionChildRetainsWideBoundaryForSharedRunDirectory() throws Exception {
        Path repositoryRoot = temporaryDirectory.resolve("repository");
        DefaultGameRepository repository = new DefaultGameRepository(repositoryRoot);
        GameInstanceID instanceId = new GameInstanceID("example");
        Task<?> root = ModrinthModpackProvider.INSTANCE.createCompletionTask(
                dependencyManager(repository),
                instanceId);

        assertEquals(
                Set.of(
                        TaskResource.repositoryOperation(repositoryRoot),
                        TaskResource.gameInstance(repositoryRoot.resolve("versions/example")),
                        TaskResource.gameDirectory(repositoryRoot)),
                root.getResources());
        Task<?> resolver = root.getDependents().iterator().next();
        resolver.execute();

        assertEquals(
                Set.of(TaskResource.gameDirectory(repositoryRoot)),
                Set.copyOf(TaskResource.normalize(
                        resolver.getDependencies().iterator().next().getResources())));
    }

    /// Different isolated instance roots can acquire concurrently while a duplicate instance waits.
    ///
    /// @throws Exception if resource preparation or acquisition fails
    @Test
    void isolatedInstanceRootsRemainParallelAndSameInstanceRootsSerialize() throws Exception {
        Path repositoryRoot = temporaryDirectory.resolve("repository");
        Path runRoot = temporaryDirectory.resolve("runs");
        TrackingRunRepository repository = new TrackingRunRepository(repositoryRoot, runRoot);
        DefaultDependencyManager dependencyManager = dependencyManager(repository);
        Task<?> first = CurseModpackProvider.INSTANCE.createCompletionTask(
                dependencyManager,
                new GameInstanceID("first"));
        Task<?> second = CurseModpackProvider.INSTANCE.createCompletionTask(
                dependencyManager,
                new GameInstanceID("second"));
        Task<?> duplicate = CurseModpackProvider.INSTANCE.createCompletionTask(
                dependencyManager,
                new GameInstanceID("first"));
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResourceLockManager.Lease firstLease = manager.acquire(manager.createOwner(
                manager.createExecution(), null, first.getResources())).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Lease secondLease = manager.acquire(manager.createOwner(
                manager.createExecution(), null, second.getResources())).get(5, TimeUnit.SECONDS);
        CompletableFuture<TaskResourceLockManager.Lease> duplicateLease = manager.acquire(manager.createOwner(
                manager.createExecution(), null, duplicate.getResources()));

        assertFalse(duplicateLease.isDone());
        firstLease.close();
        duplicateLease.get(5, TimeUnit.SECONDS).close();
        secondLease.close();
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Completion construction cannot begin until an existing holder releases the selected instance.
    ///
    /// @throws Exception if asynchronous execution or synchronization fails
    @Test
    void completionConstructionRunsUnderOuterInstanceOwnership() throws Exception {
        Path repositoryRoot = temporaryDirectory.resolve("repository");
        Path runRoot = temporaryDirectory.resolve("runs");
        TrackingRunRepository repository = new TrackingRunRepository(repositoryRoot, runRoot);
        DefaultDependencyManager dependencyManager = dependencyManager(repository);
        GameInstanceID instanceId = new GameInstanceID("example");
        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Task<?> holder = Task.runAsync(() -> {
            holderStarted.countDown();
            releaseHolder.await();
        }).setResources(TaskResource.gameInstance(repository.getInstanceRoot(instanceId)));
        TaskResourceLockManager manager = new TaskResourceLockManager();
        CompletableFuture<Boolean> holderResult = execute(holder, manager);
        assertTrue(holderStarted.await(5, TimeUnit.SECONDS));

        Task<?> completion = CurseModpackProvider.INSTANCE.createCompletionTask(dependencyManager, instanceId);
        CompletableFuture<Boolean> completionResult = execute(completion, manager);
        try {
            assertFalse(repository.awaitCompletionConstruction(200, TimeUnit.MILLISECONDS));
        } finally {
            releaseHolder.countDown();
        }

        assertTrue(repository.awaitCompletionConstruction(5, TimeUnit.SECONDS));
        assertTrue(holderResult.get(5, TimeUnit.SECONDS));
        assertTrue(completionResult.get(5, TimeUnit.SECONDS));
    }

    /// Creates real download and cache collaborators without performing network access.
    ///
    /// @param repository repository exposed by the dependency manager
    /// @return dependency manager suitable for deferred resource tests
    private DefaultDependencyManager dependencyManager(DefaultGameRepository repository) {
        return new DefaultDependencyManager(
                repository,
                new MojangDownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("cache")));
    }

    /// Executes one metadata resolver directly and verifies its handoff declaration without running network work.
    ///
    /// @param provider provider creating the deferred completion root
    /// @param dependencyManager repository and download collaborators
    /// @param instanceId selected instance
    /// @param repository tracking repository used by the provider
    /// @param repositoryRoot expected repository metadata root
    /// @param expectedRootResources resources retained across resolution and completion
    /// @param expectedChildResources expected precise child resource set
    /// @throws Exception if deferred child construction fails
    private static void assertCompletionHandoff(
            ModpackProvider provider,
            DefaultDependencyManager dependencyManager,
            GameInstanceID instanceId,
            TrackingRunRepository repository,
            Path repositoryRoot,
            @Unmodifiable Set<TaskResource> expectedRootResources,
            @Unmodifiable Set<TaskResource> expectedChildResources) throws Exception {
        Task<?> root = provider.createCompletionTask(dependencyManager, instanceId);
        assertEquals(expectedRootResources, root.getResources());
        assertEquals(1, root.getDependents().size());
        Task<?> resolver = root.getDependents().iterator().next();
        assertEquals(
                Set.of(TaskResource.repositoryMetadata(repositoryRoot)),
                resolver.getResources());
        assertTrue(resolver.releasesResourcesBeforeDependencies());
        int requestsBeforeResolution = repository.instanceRootRequests();

        resolver.execute();

        assertTrue(repository.instanceRootRequests() > requestsBeforeResolution);
        assertEquals(1, resolver.getDependencies().size());
        assertEquals(expectedChildResources, resolver.getDependencies().iterator().next().getResources());
    }

    /// Executes one task asynchronously with an isolated resource manager.
    ///
    /// @param task root task
    /// @param manager isolated resource manager
    /// @return future containing the task success state
    private static CompletableFuture<Boolean> execute(Task<?> task, TaskResourceLockManager manager) {
        return CompletableFuture.supplyAsync(() -> new AsyncTaskExecutor(task, manager).test());
    }

    /// Repository fixture with a per-instance run root and observable instance-root lookup count.
    @NotNullByDefault
    private static final class TrackingRunRepository extends DefaultGameRepository {
        /// Root containing isolated run directories.
        private final Path runRoot;

        /// Number of instance-root path requests.
        private int instanceRootRequests;

        /// Signals the first completion constructor call that obtains an instance manager.
        private final CountDownLatch completionConstruction = new CountDownLatch(1);

        /// Creates a repository with a separate isolated run root.
        ///
        /// @param repositoryRoot repository base directory
        /// @param runRoot root containing isolated run directories
        private TrackingRunRepository(Path repositoryRoot, Path runRoot) {
            super(repositoryRoot);
            this.runRoot = runRoot;
        }

        /// Returns and records the selected instance metadata directory.
        ///
        /// @param instanceId selected instance
        /// @return instance metadata directory
        @Override
        public Path getInstanceRoot(GameInstanceID instanceId) {
            ++instanceRootRequests;
            return super.getInstanceRoot(instanceId);
        }

        /// Returns the selected instance's separate run directory.
        ///
        /// @param instanceId selected instance
        /// @return isolated run directory
        @Override
        public Path getRunDirectory(GameInstanceID instanceId) {
            return runRoot.resolve(instanceId.id());
        }

        /// Records completion construction when an instance-scoped mod manager is obtained.
        ///
        /// @param instanceId selected instance
        /// @return mod manager for the selected instance
        @Override
        public ModManager getModManager(GameInstanceID instanceId) {
            completionConstruction.countDown();
            return super.getModManager(instanceId);
        }

        /// Returns how many times the instance metadata path was requested.
        ///
        /// @return instance-root request count
        private int instanceRootRequests() {
            return instanceRootRequests;
        }

        /// Waits for a completion constructor to obtain an instance manager.
        ///
        /// @param timeout maximum wait duration
        /// @param unit timeout unit
        /// @return whether construction was observed before the timeout
        /// @throws InterruptedException if the waiting test thread is interrupted
        private boolean awaitCompletionConstruction(long timeout, TimeUnit unit) throws InterruptedException {
            return completionConstruction.await(timeout, unit);
        }
    }
}
