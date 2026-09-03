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
package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.setting.GameDirectory;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.ui.swing.page.instances.management.RepositoryInstanceLifecycleService;
import space.minecraftstl.xyml.util.FileSaver;
import space.minecraftstl.xyml.util.PortablePath;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that repository lifecycle disk phases can be separated from the catalog refresh.
@NotNullByDefault
public final class RepositoryInstanceLifecycleServiceTest {
    /// Temporary repository roots used by lifecycle fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// Duplication remains absent from the loaded catalog until the explicit refresh phase runs.
    ///
    /// @throws IOException when fixture creation or duplication fails
    @Test
    public void duplicateWithoutRefreshDefersCatalogRebuild() throws IOException {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        GameInstanceID source = new GameInstanceID("source");
        GameInstanceID destination = new GameInstanceID("destination");
        installInstance(repository, source);
        createIsolatedSettings(repository, source);
        RepositoryInstanceLifecycleService service = new RepositoryInstanceLifecycleService(repository);

        service.duplicateWithoutRefresh(source, destination, true);

        assertTrue(Files.isDirectory(repository.getInstanceRoot(destination)));
        assertFalse(repository.hasInstance(destination));

        service.refreshRepository();

        assertTrue(repository.hasInstance(destination));
    }

    /// The established duplication method still refreshes the catalog before returning.
    ///
    /// @throws IOException when fixture creation or duplication fails
    @Test
    public void defaultDuplicateStillRefreshesCatalog() throws IOException {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        GameInstanceID source = new GameInstanceID("source");
        GameInstanceID destination = new GameInstanceID("destination");
        installInstance(repository, source);
        createIsolatedSettings(repository, source);
        RepositoryInstanceLifecycleService service = new RepositoryInstanceLifecycleService(repository);

        service.duplicate(source, destination, true);

        assertTrue(repository.hasInstance(destination));
    }

    /// A staged duplicate keeps using the source running directory captured during its short preparation phase.
    ///
    /// @throws IOException when fixture creation or duplication fails
    /// @throws InterruptedException when queued settings writes cannot be drained
    @Test
    public void duplicateFromSnapshotIgnoresLaterRunDirectoryChange() throws IOException, InterruptedException {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        GameInstanceID source = new GameInstanceID("source");
        GameInstanceID destination = new GameInstanceID("destination");
        installInstance(repository, source);
        GameSettings.Instance settings = Objects.requireNonNull(repository.createInstanceGameSettings(source));
        settings.getOverrideProperties().add(GameSettings.PROPERTY_RUNNING_DIRECTORY);
        Path capturedRunDirectory = temporaryDirectory.resolve("captured-run");
        Path laterRunDirectory = temporaryDirectory.resolve("later-run");
        Files.createDirectories(capturedRunDirectory);
        Files.createDirectories(laterRunDirectory);
        Files.writeString(capturedRunDirectory.resolve("captured.txt"), "captured");
        Files.writeString(laterRunDirectory.resolve("later.txt"), "later");
        settings.runningDirectoryProperty().setValue(capturedRunDirectory.toString());
        RepositoryInstanceLifecycleService service = new RepositoryInstanceLifecycleService(repository);

        XYMLGameRepository.InstanceDuplicationSnapshot snapshot = service.prepareDuplicate(source);
        settings.runningDirectoryProperty().setValue(laterRunDirectory.toString());
        FileSaver.waitForAllSaves();
        service.duplicateWithoutRefresh(source, destination, true, snapshot);

        assertTrue(Files.exists(repository.getInstanceRoot(destination).resolve("captured.txt")));
        assertFalse(Files.exists(repository.getInstanceRoot(destination).resolve("later.txt")));
    }

    /// Rename flushes queued settings before moving files and invalidates identifier-keyed settings caches.
    ///
    /// @throws IOException when fixture creation or rename fails
    /// @throws InterruptedException when the final save barrier is interrupted
    @Test
    public void renameFlushesPendingSettingsAndRekeysCache() throws IOException, InterruptedException {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        GameInstanceID source = new GameInstanceID("source");
        GameInstanceID destination = new GameInstanceID("destination");
        installInstance(repository, source);
        createIsolatedSettings(repository, source);
        repository.saveGameSettings(source);
        RepositoryInstanceLifecycleService service = new RepositoryInstanceLifecycleService(repository);

        service.renameWithoutRefresh(source, destination);
        FileSaver.waitForAllSaves();

        assertFalse(Files.exists(repository.getInstanceRoot(source)));
        assertTrue(Files.isDirectory(repository.getInstanceRoot(destination)));
        assertNull(repository.getInstanceGameSettings(source));
        assertNotNull(repository.getInstanceGameSettings(destination));
    }

    /// Rename task mutation shares the repository operation domain and locks affected instances after name resolution.
    ///
    /// @throws Exception when fixtures or deferred task construction fail
    @Test
    public void renameTaskDeclaresAffectedInstanceResources() throws Exception {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        GameInstanceID source = new GameInstanceID("source");
        GameInstanceID destination = new GameInstanceID("destination");
        GameInstanceID child = new GameInstanceID("child");
        installInstance(repository, source);
        Path childManifest = repository.getInstanceJson(child);
        Files.createDirectories(childManifest.getParent());
        JsonUtils.writeToJsonFile(childManifest,
                new GameInstanceManifest(child).withInheritsFrom(source));
        repository.refresh();
        RepositoryInstanceLifecycleService service = new RepositoryInstanceLifecycleService(repository);
        Task<?> rename = service.renameTask(source, destination);

        assertEquals(Set.of(TaskResource.repositoryMetadata(repository.getBaseDirectory())), rename.getResources());
        rename.execute();
        Set<TaskResource> mutationResources = Set.of(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(repository.getInstanceRoot(source)),
                TaskResource.gameInstance(repository.getInstanceRoot(destination)),
                TaskResource.gameInstance(repository.getInstanceRoot(child)));
        assertEquals(mutationResources, taskWithResources(rename, mutationResources).getResources());
    }

    /// Concurrent first access publishes one settings object for a shared instance identifier.
    ///
    /// @throws Exception when fixture installation or worker coordination fails
    @Test
    public void concurrentSettingsCreationPublishesSingleIdentity() throws Exception {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("settings-publication"));
        GameInstanceID instanceId = new GameInstanceID("instance");
        installInstance(repository, instanceId);
        int workerCount = 8;
        CyclicBarrier start = new CyclicBarrier(workerCount);
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        try {
            List<Future<GameSettings.Instance>> results = new java.util.ArrayList<>();
            for (int index = 0; index < workerCount; index++) {
                results.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return Objects.requireNonNull(repository.createInstanceGameSettings(instanceId));
                }));
            }

            GameSettings.Instance expected = results.get(0).get(5, TimeUnit.SECONDS);
            for (Future<GameSettings.Instance> result : results) {
                assertSame(expected, result.get(5, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// A repository-root switch waits for an active captured-root operation without blocking unrelated worker setup.
    ///
    /// @throws Exception when worker coordination fails
    @Test
    public void rootSwitchWaitsForCapturedRootOperation() throws Exception {
        Path originalRoot = temporaryDirectory.resolve("original-root").toAbsolutePath().normalize();
        Path replacementRoot = temporaryDirectory.resolve("replacement-root").toAbsolutePath().normalize();
        XYMLGameRepository repository = newRepository(originalRoot);
        CountDownLatch operationEntered = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        CountDownLatch switchStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> operation = executor.submit(() -> {
                repository.withStableBaseDirectory(originalRoot, () -> {
                    operationEntered.countDown();
                    try {
                        releaseOperation.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted test operation", exception);
                    }
                });
                return null;
            });
            assertTrue(operationEntered.await(5, TimeUnit.SECONDS));
            Future<?> rootSwitch = executor.submit(() -> {
                switchStarted.countDown();
                repository.setBaseDirectory(replacementRoot);
            });
            assertTrue(switchStarted.await(5, TimeUnit.SECONDS));

            assertThrows(TimeoutException.class, () -> rootSwitch.get(250, TimeUnit.MILLISECONDS));
            releaseOperation.countDown();
            operation.get(5, TimeUnit.SECONDS);
            rootSwitch.get(5, TimeUnit.SECONDS);
            assertEquals(replacementRoot, repository.getBaseDirectory().toAbsolutePath().normalize());
        } finally {
            releaseOperation.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// A same-thread root replacement from a captured operation fails instead of deadlocking on a lock upgrade.
    ///
    /// @throws IOException when the captured test operation unexpectedly fails
    @Test
    public void capturedOperationRejectsSameThreadRootReplacement() throws IOException {
        Path originalRoot = temporaryDirectory.resolve("captured-root").toAbsolutePath().normalize();
        Path replacementRoot = temporaryDirectory.resolve("replacement-root").toAbsolutePath().normalize();
        XYMLGameRepository repository = newRepository(originalRoot);

        repository.withStableBaseDirectory(originalRoot, () -> {
            assertThrows(IllegalStateException.class, () -> repository.setBaseDirectory(replacementRoot));
        });

        assertEquals(originalRoot, repository.getBaseDirectory().toAbsolutePath().normalize());
    }

    /// The complete lifecycle Task refreshes the catalog and persists selection through the injected settings owner.
    ///
    /// @throws Exception when fixture installation or task execution fails
    @Test
    public void renameTaskCompletesCatalogAndSelectionLifecycle() throws Exception {
        Path root = temporaryDirectory.resolve("complete-lifecycle");
        GameDirectory directory = new GameDirectory(
                GameDirectoryID.generate(),
                LocalizedText.plain("Lifecycle test"),
                PortablePath.of(root.toString()));
        LauncherSettings settings = new LauncherSettings();
        XYMLGameRepository repository = new XYMLGameRepository(directory, settings);
        GameInstanceID source = new GameInstanceID("source");
        GameInstanceID destination = new GameInstanceID("destination");
        installInstance(repository, source);
        RepositoryInstanceLifecycleService service = new RepositoryInstanceLifecycleService(repository);

        assertTrue(service.renameTask(source, destination).executor().test());

        assertFalse(repository.hasInstance(source));
        assertTrue(repository.hasInstance(destination));
        assertEquals(destination, repository.getSelectedInstance());
        assertEquals(destination, settings.getSelectedInstance(directory.getId()));
    }

    /// Finds a dynamically materialized task with one exact normalized resource declaration.
    ///
    /// @param root task graph root
    /// @param expected expected resources
    /// @return matching task
    private static Task<?> taskWithResources(Task<?> root, Set<TaskResource> expected) {
        ArrayDeque<Task<?>> pending = new ArrayDeque<>();
        Set<Task<?>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(root);
        while (!pending.isEmpty()) {
            Task<?> task = pending.removeFirst();
            if (!visited.add(task)) {
                continue;
            }
            if (task.getResources().equals(expected)) {
                return task;
            }
            pending.addAll(task.getDependents());
            pending.addAll(task.getDependencies());
        }
        throw new IllegalStateException("No task declared the expected resources: " + expected);
    }

    /// Creates an isolated repository without depending on process-global launcher settings.
    ///
    /// @param root repository root
    /// @return repository backed by the supplied root
    private static XYMLGameRepository newRepository(Path root) {
        return new XYMLGameRepository(
                new GameDirectory(
                        GameDirectoryID.generate(),
                        LocalizedText.plain("Lifecycle test"),
                        PortablePath.of(root.toString())),
                new LauncherSettings());
    }

    /// Writes and loads one minimal instance manifest.
    ///
    /// @param repository repository receiving the fixture
    /// @param instanceId fixture instance identifier
    /// @throws IOException when the manifest cannot be written
    private static void installInstance(XYMLGameRepository repository, GameInstanceID instanceId) throws IOException {
        Path manifest = repository.getInstanceJson(instanceId);
        Files.createDirectories(manifest.getParent());
        JsonUtils.writeToJsonFile(manifest, new GameInstanceManifest(instanceId));
        repository.refresh();
        assertTrue(repository.hasInstance(instanceId));
    }

    /// Creates source settings that force the duplicate operation to use the instance directory as its run directory.
    ///
    /// @param repository repository owning the instance
    /// @param instanceId source instance identifier
    private static void createIsolatedSettings(XYMLGameRepository repository, GameInstanceID instanceId) {
        GameSettings.Instance settings = Objects.requireNonNull(repository.createInstanceGameSettings(instanceId));
        settings.getOverrideProperties().add(GameSettings.PROPERTY_RUNNING_DIRECTORY);
        settings.runningDirectoryProperty().setValue("");
    }
}
