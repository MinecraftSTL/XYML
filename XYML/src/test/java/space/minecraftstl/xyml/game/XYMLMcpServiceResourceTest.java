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
import space.minecraftstl.xyml.mcp.XYMLMcpService;
import space.minecraftstl.xyml.setting.GameDirectory;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.task.TaskResource;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies MCP operations publish precise resources before the Core registry starts their task chains.
@NotNullByDefault
public final class XYMLMcpServiceResourceTest {
    /// Verifies frequently used instance operations no longer retain the conservative global fallback.
    ///
    /// @param temporaryDirectory isolated repository root
    @Test
    public void auditedOperationsDoNotUseConservativeResources(@TempDir Path temporaryDirectory) {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        XYMLMcpService service = new XYMLMcpService(repository, request -> false);
        Path mod = repository.getBaseDirectory().resolve("mods/example.jar");
        List<Task<?>> tasks = List.of(
                service.listInstances(),
                service.getInstanceSettings("first"),
                service.renameInstance("first", "renamed"),
                service.duplicateInstance("first", "copy", false),
                service.deleteInstance("first"),
                service.getModsDirectory("first"),
                service.analyzeCrash("first", "log", null),
                service.readResource("xyml://instances/first/logs/latest.log"),
                service.listLocalMods("first"),
                service.setJavaVersion("first", "17", null, false),
                service.setMemory("first", null, 4096, false),
                service.setJvmOptions("first", "-Xmx4G", false),
                service.setWindowOptions("first", 1280, 720, false, false),
                service.enableMod("first", mod.toString()),
                service.disableMod("first", mod.toString()),
                service.removeMods("first", List.of()),
                service.launchGame("first"),
                service.stopGame("first"));

        for (Task<?> task : tasks) {
            assertFalse(task.getResources().stream()
                    .anyMatch(resource -> resource.getKind() == TaskResource.Kind.CONSERVATIVE), task.toString());
        }
    }

    /// Verifies instance lifecycle, settings, and process transitions use their intended narrow resource boundaries.
    ///
    /// @param temporaryDirectory isolated repository root
    /// @throws Exception when the deferred duplication stage cannot be materialized
    @Test
    public void declaresPreciseLifecycleAndInstanceResources(@TempDir Path temporaryDirectory) throws Exception {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        XYMLMcpService service = new XYMLMcpService(repository, request -> true);
        GameInstanceID source = new GameInstanceID("source");
        GameInstanceID destination = new GameInstanceID("destination");
        installInstance(repository, source);
        createIsolatedSettings(repository, source);

        assertEquals(Set.of(
                TaskResource.gameDirectory(repository.getBaseDirectory()),
                TaskResource.configuration(SettingsManager.settingsLocation())),
                service.listInstances().getResources());

        Task<?> renameResolution = service.renameInstance(source.id(), destination.id());
        assertEquals(Set.of(TaskResource.repositoryMetadata(repository.getBaseDirectory())),
                renameResolution.getResources());
        renameResolution.execute();
        Set<TaskResource> renameMutationResources = Set.of(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(repository.getInstanceRoot(source)),
                TaskResource.gameInstance(repository.getInstanceRoot(destination)));
        assertEquals(renameMutationResources,
                taskWithResources(renameResolution, renameMutationResources).getResources());

        Task<?> duplicateResolution = service.duplicateInstance(source.id(), destination.id(), false);
        assertEquals(Set.of(
                TaskResource.configuration(repository.getInstanceConfigDirectory(source)
                        .resolve("instance-game-settings.json")),
                TaskResource.configuration(SettingsManager.gameSettingsLocation()),
                TaskResource.configuration(SettingsManager.settingsLocation())),
                duplicateResolution.getResources());
        duplicateResolution.execute();
        Set<TaskResource> duplicateMutationResources = Set.of(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(repository.getInstanceRoot(source)),
                TaskResource.gameInstance(repository.getInstanceRoot(destination)),
                TaskResource.gameDirectory(repository.getInstanceRoot(source)));
        assertEquals(duplicateMutationResources,
                taskWithResources(duplicateResolution, duplicateMutationResources).getResources());

        Task<?> deleteResolution = service.deleteInstance(source.id());
        assertEquals(Set.of(TaskResource.repositoryMetadata(repository.getBaseDirectory())),
                deleteResolution.getResources());
        deleteResolution.execute();
        Path sourceDirectory = repository.getInstanceRoot(source);
        Set<TaskResource> deleteMutationResources = Set.of(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(sourceDirectory),
                TaskResource.gameDirectory(sourceDirectory.resolveSibling(source.id() + "_removed")));
        assertEquals(deleteMutationResources,
                taskWithResources(deleteResolution, deleteMutationResources).getResources());

        assertEquals(Set.of(
                TaskResource.configuration(repository.getInstanceConfigDirectory(source)
                        .resolve("instance-game-settings.json")),
                TaskResource.configuration(SettingsManager.gameSettingsLocation()),
                TaskResource.configuration(SettingsManager.settingsLocation())),
                service.setJvmOptions(source.id(), "-Xmx4G", false).getResources());
        assertEquals(Set.of(TaskResource.gameInstance(sourceDirectory)),
                service.stopGame(source.id()).getResources());
    }

    /// Dynamic mod and log stages retain the paths resolved under their short settings snapshot.
    ///
    /// @param temporaryDirectory isolated repository root
    /// @throws Exception when a deferred resource stage cannot be materialized
    @Test
    public void materializesResolvedModAndLogResources(@TempDir Path temporaryDirectory) throws Exception {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        XYMLMcpService service = new XYMLMcpService(repository, request -> true);
        GameInstanceID instanceId = new GameInstanceID("instance");
        installInstance(repository, instanceId);
        createIsolatedSettings(repository, instanceId);
        Path instanceDirectory = repository.getInstanceRoot(instanceId).toAbsolutePath().normalize();
        Path runDirectory = repository.getRunDirectory(instanceId).toAbsolutePath().normalize();
        Path modsDirectory = repository.getModsDirectory(instanceId).toAbsolutePath().normalize();

        Task<?> modsResolution = service.listLocalMods(instanceId.id());
        modsResolution.execute();
        Set<TaskResource> modResources = Set.of(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(instanceDirectory),
                TaskResource.gameDirectory(modsDirectory));
        assertEquals(modResources, taskWithResources(modsResolution, modResources).getResources());

        Task<?> logResolution = service.readResource(
                "xyml://instances/" + instanceId.id() + "/logs/latest.log");
        logResolution.execute();
        Set<TaskResource> logResources = Set.of(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(instanceDirectory),
                TaskResource.gameDirectory(runDirectory));
        assertEquals(logResources, taskWithResources(logResolution, logResources).getResources());

        Task<?> crashResolution = service.analyzeCrash(instanceId.id(), "supplied log", null);
        assertEquals(Set.of(
                        TaskResource.gameInstance(instanceDirectory),
                        TaskResource.configuration(SettingsManager.gameSettingsLocation()),
                        TaskResource.configuration(SettingsManager.settingsLocation())),
                crashResolution.getResources());
        crashResolution.execute();
        assertEquals(TaskResource.Kind.ORCHESTRATION,
                onlyDependency(crashResolution).getResources().iterator().next().getKind());
    }

    /// A dynamic child rejects a repository-root switch instead of reading the captured path from the old repository.
    @Test
    public void dynamicChildRejectsRepositoryRootSwitch(@TempDir Path temporaryDirectory) throws Exception {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("original"));
        GameInstanceID instanceId = new GameInstanceID("instance");
        installInstance(repository, instanceId);
        createIsolatedSettings(repository, instanceId);
        XYMLMcpService service = new XYMLMcpService(repository, request -> true);
        Task<?> resolution = service.listLocalMods(instanceId.id());
        resolution.execute();
        Task<?> child = onlyDependency(resolution);

        repository.setBaseDirectory(temporaryDirectory.resolve("replacement"));

        assertFalse(child.test());
        assertInstanceOf(IllegalStateException.class, child.getException());
    }

    /// Destructive confirmation remains dormant until its independently resourced Task stage executes.
    ///
    /// @param temporaryDirectory isolated repository root
    /// @throws Exception when fixture or staged task construction fails
    @Test
    public void defersDeletionConfirmationIntoSettingsResourceStage(@TempDir Path temporaryDirectory) throws Exception {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        GameInstanceID source = new GameInstanceID("source");
        installInstance(repository, source);
        AtomicInteger confirmations = new AtomicInteger();
        XYMLMcpService service = new XYMLMcpService(repository, request -> {
            confirmations.incrementAndGet();
            return false;
        });

        Task<?> deletion = service.deleteInstance(source.id());

        assertEquals(0, confirmations.get());
        deletion.execute();
        assertEquals(0, confirmations.get());
        Path instanceDirectory = repository.getInstanceRoot(source);
        Set<TaskResource> confirmationResources = Set.of(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(instanceDirectory),
                TaskResource.gameDirectory(instanceDirectory.resolveSibling(source.id() + "_removed")));
        taskWithResources(deletion, confirmationResources);
        assertTrue(deletion.executor().test());
        assertEquals(1, confirmations.get());
    }

    /// Instance deletion retains its exact target lease while confirmation is pending, preventing ABA replacement.
    ///
    /// @param temporaryDirectory isolated repository root
    /// @throws Exception when asynchronous task coordination fails
    @Test
    public void deletionConfirmationRetainsTargetInstanceLease(@TempDir Path temporaryDirectory) throws Exception {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        GameInstanceID source = new GameInstanceID("source");
        installInstance(repository, source);
        CountDownLatch confirmationEntered = new CountDownLatch(1);
        CountDownLatch releaseConfirmation = new CountDownLatch(1);
        XYMLMcpService service = new XYMLMcpService(repository, request -> {
            confirmationEntered.countDown();
            try {
                assertTrue(releaseConfirmation.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while holding deletion confirmation", exception);
            }
            return false;
        });
        TaskExecutor deletion = service.deleteInstance(source.id()).executor();
        AtomicBoolean competitorRan = new AtomicBoolean();
        CountDownLatch competitorStarted = new CountDownLatch(1);
        TaskExecutor competitor = Task.runAsync(() -> competitorRan.set(true))
                .setResources(TaskResource.gameInstance(repository.getInstanceRoot(source)))
                .executor();
        competitor.subscribeTaskListener(new TaskListener() {
            /// Records that the competing executor started and reached resource arbitration.
            @Override
            public void onStart() {
                competitorStarted.countDown();
            }
        });

        CompletableFuture<Boolean> deletionResult = CompletableFuture.supplyAsync(deletion::test);
        try {
            assertTrue(confirmationEntered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Boolean> competitorResult = CompletableFuture.supplyAsync(competitor::test);
            assertTrue(competitorStarted.await(5, TimeUnit.SECONDS));
            assertFalse(competitorRan.get());
            assertThrows(TimeoutException.class, () -> competitorResult.get(250, TimeUnit.MILLISECONDS));

            releaseConfirmation.countDown();
            assertTrue(deletionResult.get(5, TimeUnit.SECONDS));
            assertTrue(competitorResult.get(5, TimeUnit.SECONDS));
            assertTrue(competitorRan.get());
        } finally {
            releaseConfirmation.countDown();
        }
    }

    /// Different instance deletions reach their confirmation stages concurrently after serialized name resolution.
    ///
    /// @param temporaryDirectory isolated repository root
    /// @throws Exception when asynchronous task coordination fails
    @Test
    public void differentInstanceDeletionsOverlapAfterResolution(@TempDir Path temporaryDirectory) throws Exception {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        GameInstanceID first = new GameInstanceID("first");
        GameInstanceID second = new GameInstanceID("second");
        writeInstance(repository, first);
        writeInstance(repository, second);
        repository.refresh();
        CyclicBarrier confirmations = new CyclicBarrier(2);
        AtomicInteger confirmationCount = new AtomicInteger();
        XYMLMcpService service = new XYMLMcpService(repository, request -> {
            confirmationCount.incrementAndGet();
            try {
                confirmations.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while proving cross-instance concurrency", exception);
            } catch (Exception exception) {
                throw new AssertionError("Different instance confirmations did not overlap", exception);
            }
            return false;
        });

        CompletableFuture<Boolean> firstResult = CompletableFuture.supplyAsync(
                () -> service.deleteInstance(first.id()).executor().test());
        CompletableFuture<Boolean> secondResult = CompletableFuture.supplyAsync(
                () -> service.deleteInstance(second.id()).executor().test());

        assertTrue(firstResult.get(10, TimeUnit.SECONDS));
        assertTrue(secondResult.get(10, TimeUnit.SECONDS));
        assertEquals(2, confirmationCount.get());
    }

    /// Mod removal defers directory scanning until the precise instance and mods-directory lease is active.
    ///
    /// @param temporaryDirectory isolated repository root
    /// @throws Exception when the deferred removal stage cannot be materialized
    @Test
    public void modRemovalDefersScanningToPreciseResourceStage(@TempDir Path temporaryDirectory) throws Exception {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        GameInstanceID instanceId = new GameInstanceID("instance");
        installInstance(repository, instanceId);
        createIsolatedSettings(repository, instanceId);
        Path missingMod = repository.getModsDirectory(instanceId).resolve("missing.jar");
        AtomicInteger confirmations = new AtomicInteger();
        XYMLMcpService service = new XYMLMcpService(repository, request -> {
            confirmations.incrementAndGet();
            return true;
        });
        Task<?> operation = service.removeMods(instanceId.id(), List.of(missingMod.toString()));

        operation.execute();

        Task<?> confirmation = onlyDependency(operation);
        assertEquals(Set.of(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(repository.getInstanceRoot(instanceId)),
                TaskResource.gameDirectory(repository.getModsDirectory(instanceId))),
                confirmation.getResources());
        assertFalse(confirmation.executor().test());
        assertEquals(0, confirmations.get());
    }

    /// Cancellation while confirmation is open prevents an approved instance-deletion mutation from starting.
    @Test
    public void cancellationDuringConfirmationPreventsInstanceDeletion(@TempDir Path temporaryDirectory)
            throws Exception {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        GameInstanceID source = new GameInstanceID("source");
        installInstance(repository, source);
        CountDownLatch confirmationEntered = new CountDownLatch(1);
        CountDownLatch releaseConfirmation = new CountDownLatch(1);
        XYMLMcpService service = new XYMLMcpService(repository, request -> {
            confirmationEntered.countDown();
            try {
                assertTrue(releaseConfirmation.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting deletion approval", exception);
            }
            return true;
        });
        TaskExecutor deletion = service.deleteInstance(source.id()).executor();
        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(deletion::test);

        try {
            assertTrue(confirmationEntered.await(5, TimeUnit.SECONDS));
            deletion.cancel();
            releaseConfirmation.countDown();
            assertFalse(result.get(5, TimeUnit.SECONDS));
            assertTrue(repository.hasInstance(source));
            assertTrue(Files.isDirectory(repository.getInstanceRoot(source)));
        } finally {
            releaseConfirmation.countDown();
        }
    }

    /// A failed disk mutation still refreshes the catalog from its current filesystem state.
    ///
    /// @param temporaryDirectory isolated repository root
    /// @throws IOException when fixture manifests cannot be written
    @Test
    public void refreshesRepositoryAfterLifecycleMutationFailure(@TempDir Path temporaryDirectory) throws IOException {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        XYMLMcpService service = new XYMLMcpService(repository, request -> true);
        GameInstanceID source = new GameInstanceID("source");
        GameInstanceID destination = new GameInstanceID("destination");
        installInstance(repository, source);
        writeInstance(repository, destination);

        TaskExecutor executor = service.renameInstance(source.id(), destination.id()).executor();

        assertFalse(executor.test());
        assertTrue(repository.hasInstance(source));
        assertTrue(repository.hasInstance(destination));
    }

    /// Cancellation immediately after a committed mutation still runs the repository-refresh cleanup.
    ///
    /// @param temporaryDirectory isolated repository root
    /// @throws Exception when the source fixture or deferred rename stage cannot be prepared
    @Test
    public void refreshesRepositoryAfterCommittedMutationIsCancelled(@TempDir Path temporaryDirectory)
            throws Exception {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        XYMLMcpService service = new XYMLMcpService(repository, request -> true);
        GameInstanceID source = new GameInstanceID("source");
        GameInstanceID destination = new GameInstanceID("destination");
        installInstance(repository, source);
        Task<?> operation = service.renameInstance(source.id(), destination.id());
        operation.execute();
        Set<TaskResource> mutationResources = Set.of(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(repository.getInstanceRoot(source)),
                TaskResource.gameInstance(repository.getInstanceRoot(destination)));
        Task<?> mutation = taskWithResources(operation, mutationResources);
        TaskExecutor executor = onlyDependency(operation).executor();
        mutation.onDone().register(executor::cancel);

        assertFalse(executor.test());
        assertFalse(repository.hasInstance(source));
        assertTrue(repository.hasInstance(destination));
    }

    /// An Error after a committed mutation still refreshes the repository and remains the terminal failure.
    ///
    /// @param temporaryDirectory isolated repository root
    /// @throws Exception when the deferred rename stage cannot be materialized
    @Test
    public void refreshesRepositoryAfterCommittedMutationListenerError(@TempDir Path temporaryDirectory)
            throws Exception {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        XYMLMcpService service = new XYMLMcpService(repository, request -> true);
        GameInstanceID source = new GameInstanceID("source");
        GameInstanceID destination = new GameInstanceID("destination");
        installInstance(repository, source);
        Task<?> operation = service.renameInstance(source.id(), destination.id());
        operation.execute();
        Set<TaskResource> mutationResources = Set.of(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(repository.getInstanceRoot(source)),
                TaskResource.gameInstance(repository.getInstanceRoot(destination)));
        Task<?> mutation = taskWithResources(operation, mutationResources);
        AssertionError original = new AssertionError("terminal listener failure");
        mutation.onDone().register(() -> {
            throw original;
        });
        TaskExecutor executor = onlyDependency(operation).executor();

        assertFalse(executor.test());
        assertSame(original, executor.getFailure());
        assertFalse(repository.hasInstance(source));
        assertTrue(repository.hasInstance(destination));
    }

    /// A duplicate stage uses the immutable settings snapshot captured by its short resolution phase.
    ///
    /// @param temporaryDirectory isolated repository root
    /// @throws Exception when the fixture or deferred duplicate stage cannot be prepared
    @Test
    public void duplicateUsesCapturedSettingsAfterResolution(@TempDir Path temporaryDirectory)
            throws Exception {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        XYMLMcpService service = new XYMLMcpService(repository, request -> true);
        GameInstanceID source = new GameInstanceID("source");
        GameInstanceID destination = new GameInstanceID("destination");
        installInstance(repository, source);
        createIsolatedSettings(repository, source);
        GameSettings.Instance settings = Objects.requireNonNull(repository.getInstanceGameSettings(source));
        settings.getOverrideProperties().add(GameSettings.PROPERTY_JVM_OPTIONS);
        settings.jvmOptionsProperty().setValue("-Dsnapshot=captured");
        FileSaver.waitForAllSaves();
        Task<?> operation = service.duplicateInstance(source.id(), destination.id(), false);
        operation.execute();
        Task<?> mutation = taskNamed(operation, "Duplicate MCP game instance");
        settings.jvmOptionsProperty().setValue("-Dsnapshot=changed");
        FileSaver.waitForAllSaves();

        assertTrue(mutation.test());
        GameSettings.Instance copied = Objects.requireNonNull(repository.getInstanceGameSettings(destination));
        assertEquals("-Dsnapshot=captured", copied.jvmOptionsProperty().getValue());
    }

    /// Invalid setting input is rejected before observable instance state can be partially mutated.
    ///
    /// @param temporaryDirectory isolated repository root
    /// @throws IOException when the instance fixture cannot be written
    @Test
    public void invalidSettingInputDoesNotPartiallyMutateInstance(@TempDir Path temporaryDirectory)
            throws IOException {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        XYMLMcpService service = new XYMLMcpService(repository, request -> true);
        GameInstanceID instanceId = new GameInstanceID("instance");
        installInstance(repository, instanceId);
        createIsolatedSettings(repository, instanceId);
        GameSettings.Instance settings = Objects.requireNonNull(repository.getInstanceGameSettings(instanceId));
        String before = LauncherSettings.SETTINGS_GSON.toJson(settings);

        assertFalse(service.setJvmOptions(instanceId.id(), null, false).executor().test());
        assertEquals(before, LauncherSettings.SETTINGS_GSON.toJson(settings));

        assertFalse(service.setJavaVersion(
                instanceId.id(), null, String.valueOf((char) 0), false).executor().test());
        assertEquals(before, LauncherSettings.SETTINGS_GSON.toJson(settings));
    }

    /// Returns the sole dynamically materialized successor of one composition node.
    ///
    /// @param task composition node already executed once for test inspection
    /// @return sole successor task
    private static Task<?> onlyDependency(Task<?> task) {
        assertEquals(1, task.getDependencies().size());
        return task.getDependencies().iterator().next();
    }

    /// Finds a materialized task whose normalized resource declaration matches the expected set.
    ///
    /// @param root task graph root
    /// @param expected expected normalized resources
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

    /// Finds a materialized task by its stable diagnostic name.
    ///
    /// @param root task graph root
    /// @param name expected task name
    /// @return matching task
    private static Task<?> taskNamed(Task<?> root, String name) {
        ArrayDeque<Task<?>> pending = new ArrayDeque<>();
        Set<Task<?>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(root);
        while (!pending.isEmpty()) {
            Task<?> task = pending.removeFirst();
            if (!visited.add(task)) {
                continue;
            }
            if (task.getName().equals(name)) {
                return task;
            }
            pending.addAll(task.getDependents());
            pending.addAll(task.getDependencies());
        }
        throw new IllegalStateException("No task used the expected name: " + name);
    }

    /// Creates one repository without touching global launcher configuration.
    ///
    /// @param root repository root
    /// @return repository backed by the supplied path
    private static XYMLGameRepository newRepository(Path root) {
        return new XYMLGameRepository(
                new GameDirectory(
                        GameDirectoryID.generate(),
                        LocalizedText.plain("MCP resource test"),
                        PortablePath.of(root.toString())),
                new LauncherSettings());
    }

    /// Writes and loads one minimal instance manifest.
    ///
    /// @param repository repository receiving the fixture
    /// @param instanceId fixture instance identifier
    /// @throws IOException when the manifest cannot be written
    private static void installInstance(XYMLGameRepository repository, GameInstanceID instanceId) throws IOException {
        writeInstance(repository, instanceId);
        repository.refresh();
    }

    /// Writes one minimal manifest without rebuilding the in-memory catalog.
    ///
    /// @param repository repository receiving the fixture
    /// @param instanceId fixture instance identifier
    /// @throws IOException when the manifest cannot be written
    private static void writeInstance(XYMLGameRepository repository, GameInstanceID instanceId) throws IOException {
        Path manifest = repository.getInstanceJson(instanceId);
        Files.createDirectories(manifest.getParent());
        JsonUtils.writeToJsonFile(manifest, new GameInstanceManifest(instanceId));
    }

    /// Creates settings that resolve the effective run directory without consulting global presets.
    ///
    /// @param repository repository owning the instance
    /// @param instanceId fixture instance identifier
    private static void createIsolatedSettings(XYMLGameRepository repository, GameInstanceID instanceId) {
        GameSettings.Instance settings = Objects.requireNonNull(repository.createInstanceGameSettings(instanceId));
        settings.getOverrideProperties().add(GameSettings.PROPERTY_RUNNING_DIRECTORY);
        settings.runningDirectoryProperty().setValue("");
    }
}
