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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.util.CacheRepository;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies semantic resource normalization and owner-aware asynchronous lock management.
@NotNullByDefault
public final class TaskResourceLockManagerTest {
    /// Temporary filesystem root used to construct normalized path resources.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies task declarations are immutable, non-empty, and conservative by default.
    @Test
    public void taskResourcesHaveSafeImmutableDefaults() {
        Task<Void> task = emptyTask();

        assertEquals(Set.of(TaskResource.conservative()), task.getResources());
        assertThrows(UnsupportedOperationException.class, () -> task.getResources().clear());
        assertEquals(
                List.of(TaskResource.global()),
                TaskResourcePathIdentity.resolve(List.of(TaskResource.conservative())));
        assertThrows(IllegalArgumentException.class, () -> task.setResources(
                TaskResource.conservative(),
                TaskResource.global()));

        TaskResource target = TaskResource.downloadTarget(temporaryDirectory.resolve("downloads/../game.jar"));
        assertSame(task, task.setResources(target));
        assertEquals(Set.of(target), task.getResources());

        TaskResource instance = TaskResource.gameInstance(temporaryDirectory.resolve("instance"));
        TaskResource nestedRunDirectory = TaskResource.gameDirectory(
                temporaryDirectory.resolve("instance/run"));
        task.setResources(instance, nestedRunDirectory);
        assertEquals(Set.of(instance, nestedRunDirectory), task.getResources());
        assertEquals(Set.of(instance, nestedRunDirectory), task.getResourceDeclarations());
        assertThrows(UnsupportedOperationException.class, () -> task.getResourceDeclarations().clear());

        TaskResource nestedInstance = TaskResource.gameInstance(temporaryDirectory.resolve("instance/nested"));
        task.setResources(instance, nestedInstance);
        assertEquals(Set.of(instance), task.getResources());
        assertEquals(Set.of(instance, nestedInstance), task.getResourceDeclarations());
    }

    /// Verifies presentation-only wrappers retain precise resources while executable continuations stay conservative.
    @Test
    public void presentationWrappersRetainResourceSnapshot() {
        TaskResource target = TaskResource.downloadTarget(temporaryDirectory.resolve("wrapped.jar"));
        Task<Void> task = emptyTask().setResources(target);

        List<Task<Void>> presentationWrappers = List.of(
                task.withStage("download"),
                task.withCounter("download"),
                task.withStagesHints("download"),
                task.withFakeProgress("download", () -> true, 1.0D));

        presentationWrappers.forEach(wrapper -> assertEquals(Set.of(target), wrapper.getResources()));
        assertEquals(
                Set.of(TaskResource.conservative()),
                task.thenRunAsync(() -> { }).getResources());
    }

    /// Verifies direct task composition uses a non-owning orchestration node while arbitrary callbacks remain
    /// conservative.
    @Test
    public void directTaskCompositionUsesOrchestrationResource() {
        TaskResource firstResource = TaskResource.downloadTarget(temporaryDirectory.resolve("compose-first.jar"));
        TaskResource secondResource = TaskResource.downloadTarget(temporaryDirectory.resolve("compose-second.jar"));
        Task<?> first = emptyTask().setResources(firstResource);
        Task<?> second = emptyTask().setResources(secondResource);

        Task<?> composed = first.thenComposeAsync(second);
        Task<?> failureTolerant = first.withComposeAsync(second);

        assertEquals(Set.of(TaskResource.orchestration()), composed.getResources());
        assertEquals(Set.of(TaskResource.orchestration()), failureTolerant.getResources());
        assertEquals(Set.of(TaskResource.conservative()), first.thenComposeAsync(() -> second).getResources());
        assertEquals(
                Set.of(TaskResource.orchestration()),
                first.thenComposeAsync(() -> second).asOrchestration().getResources());
        assertEquals(Set.of(TaskResource.orchestration()), first.wrapResult().getResources());
        assertEquals(Set.of(TaskResource.orchestration()), Task.completed(null).getResources());
        assertEquals(Set.of(TaskResource.orchestration()), Task.runSequentially().getResources());
    }

    /// Verifies every file download declares its normalized exact destination without starting network work.
    @Test
    public void fileDownloadDeclaresExactTargetResource() {
        Path targetPath = temporaryDirectory.resolve("downloads/../game.jar");
        FileDownloadTask task = new FileDownloadTask(URI.create("https://example.invalid/game.jar"), targetPath);

        assertEquals(Set.of(TaskResource.downloadTarget(targetPath)), task.getResources());
        assertEquals(targetPath, task.getPath());
    }

    /// Verifies cache-only fetchers follow their configured cache transaction while target downloads remain exact.
    @Test
    public void cacheOnlyFetchersDeclareExclusiveCacheOperation() {
        Path commonDirectory = temporaryDirectory.resolve("fetch-cache");
        CacheRepository cacheRepository = new CacheRepository();
        cacheRepository.changeDirectory(commonDirectory);
        URI source = URI.create("https://example.invalid/metadata.json");
        GetTask text = new GetTask(source);
        BoundedTextFetchTask bounded = new BoundedTextFetchTask(List.of(source), 1_024L);
        CacheFileTask cachedFile = new CacheFileTask(source);
        Path targetPath = temporaryDirectory.resolve("target.json");
        FileDownloadTask targetDownload = new FileDownloadTask(source, targetPath);

        text.setCacheRepository(cacheRepository);
        bounded.setCacheRepository(cacheRepository);
        cachedFile.setCacheRepository(cacheRepository);
        targetDownload.setCacheRepository(cacheRepository);

        Set<TaskResource> cacheResources = Set.of(TaskResource.cacheOperation(commonDirectory.resolve("cache")));
        assertEquals(cacheResources, text.getResources());
        assertEquals(cacheResources, bounded.getResources());
        assertEquals(cacheResources, cachedFile.getResources());
        targetDownload.setCaching(true);
        assertEquals(Set.of(TaskResource.downloadTarget(targetPath)), targetDownload.getResources());
        targetDownload.setCaching(false);
        assertEquals(Set.of(TaskResource.downloadTarget(targetPath)), targetDownload.getResources());
        assertEquals(
                Set.of(TaskResource.orchestration()),
                text.thenGetJsonAsync(Object.class).getResources());
    }

    /// Verifies mutable cache reconfiguration cannot redirect a queued cache task outside its declared resource.
    @Test
    public void cacheOnlyFetcherRejectsChangedCacheDirectory() {
        CacheRepository cacheRepository = new CacheRepository();
        cacheRepository.changeDirectory(temporaryDirectory.resolve("first-cache-root"));
        GetTask task = new GetTask(URI.create("https://example.invalid/metadata.json"));
        task.setCacheRepository(cacheRepository);

        cacheRepository.changeDirectory(temporaryDirectory.resolve("second-cache-root"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, task::execute);
        assertTrue(failure.getMessage().contains("Cache directory changed"));
    }

    /// Verifies a symbolic-link directory and its real directory serialize the same missing target path.
    @Test
    public void symbolicLinkDirectorySharesIdentityForMissingLeaf() throws Exception {
        Path realDirectory = Files.createDirectories(temporaryDirectory.resolve("real-directory"));
        Path aliasDirectory = temporaryDirectory.resolve("alias-directory");
        try {
            Files.createSymbolicLink(aliasDirectory, realDirectory);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + unavailable.getMessage());
            return;
        }

        Path realTarget = realDirectory.resolve("nested/missing.jar");
        Path aliasTarget = aliasDirectory.resolve("nested/missing.jar");
        assertAliasSharesIdentity(realTarget, aliasTarget);
    }

    /// Verifies a Windows junction and its target serialize the same missing descendant.
    @Test
    public void windowsJunctionDirectorySharesIdentityForMissingLeaf() throws Exception {
        Assumptions.assumeTrue(File.separatorChar == '\\', "Windows junction test");
        Path realDirectory = Files.createDirectories(temporaryDirectory.resolve("junction-real-directory"));
        Path aliasDirectory = temporaryDirectory.resolve("junction-alias-directory");
        Process junctionCreation = new ProcessBuilder(
                "cmd.exe",
                "/d",
                "/c",
                "mklink",
                "/J",
                aliasDirectory.toString(),
                realDirectory.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        boolean junctionCreated = junctionCreation.waitFor(5, TimeUnit.SECONDS);
        if (!junctionCreated) {
            junctionCreation.destroyForcibly();
        }
        Assumptions.assumeTrue(junctionCreated && junctionCreation.exitValue() == 0,
                "Junction creation is unavailable");

        try {
            assertAliasSharesIdentity(
                    realDirectory.resolve("nested/missing.jar"),
                    aliasDirectory.resolve("nested/missing.jar"));
        } finally {
            Files.deleteIfExists(aliasDirectory);
        }
    }

    /// Asserts that an alias retains its target identity and remains mutually exclusive with the real path.
    private static void assertAliasSharesIdentity(Path realTarget, Path aliasTarget) throws Exception {
        assertFalse(Files.exists(realTarget, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(aliasTarget, LinkOption.NOFOLLOW_LINKS));

        List<TaskResource> realIdentity = TaskResourcePathIdentity.resolve(
                List.of(TaskResource.downloadTarget(realTarget)));
        List<TaskResource> aliasIdentity = TaskResourcePathIdentity.resolve(
                List.of(TaskResource.downloadTarget(aliasTarget)));
        assertTrue(aliasIdentity.containsAll(realIdentity));
        assertTrue(realIdentity.stream().anyMatch(realResource ->
                aliasIdentity.stream().anyMatch(realResource::conflictsWith)));

        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResourceLockManager.Lease firstLease = manager.acquire(
                rootOwner(manager, TaskResource.downloadTarget(realTarget))).get(5, TimeUnit.SECONDS);
        CompletableFuture<TaskResourceLockManager.Lease> aliasFuture = manager.acquire(
                rootOwner(manager, TaskResource.downloadTarget(aliasTarget)));

        assertFalse(aliasFuture.isDone());
        firstLease.close();
        TaskResourceLockManager.Lease aliasLease = aliasFuture.get(5, TimeUnit.SECONDS);
        aliasLease.close();
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a missing descendant below an existing regular file fails closed to the global resource.
    @Test
    public void missingDescendantBelowRegularFileFallsBackToGlobal() throws Exception {
        Path existingFile = Files.createFile(temporaryDirectory.resolve("existing-file.jar"));
        Path impossibleDescendant = existingFile.resolve("child.jar");

        assertEquals(
                List.of(TaskResource.global()),
                TaskResourcePathIdentity.resolve(List.of(TaskResource.downloadTarget(impossibleDescendant))));
    }

    /// Verifies semantic exact-file categories still conflict when they represent the same normalized path.
    @Test
    public void addonFileConflictsWithDownloadTargetAtSamePath() {
        Path path = temporaryDirectory.resolve("mods/../mods/example.jar");
        TaskResource addonFile = TaskResource.addonFile(path);
        TaskResource downloadTarget = TaskResource.downloadTarget(path);

        assertEquals(TaskResource.Kind.ADDON_FILE, addonFile.getKind());
        assertEquals(path.toAbsolutePath().normalize(), addonFile.getPath());
        assertTrue(addonFile.conflictsWith(downloadTarget));
        assertTrue(downloadTarget.conflictsWith(addonFile));
    }

    /// Verifies world and NBT resources conflict by filesystem range without serializing distinct saved worlds.
    @Test
    public void gameWorldAndNbtResourcesPreserveWorldLevelConcurrency() {
        Path instancePath = temporaryDirectory.resolve("instances/example");
        Path firstWorldPath = instancePath.resolve("saves/first");
        Path secondWorldPath = instancePath.resolve("saves/second");
        TaskResource instance = TaskResource.gameInstance(instancePath);
        TaskResource firstWorld = TaskResource.gameWorld(firstWorldPath);
        TaskResource secondWorld = TaskResource.gameWorld(secondWorldPath);
        TaskResource levelData = TaskResource.nbtFile(firstWorldPath.resolve("level.dat"));
        TaskResource regionDirectory = TaskResource.nbtDirectory(firstWorldPath.resolve("region"));
        TaskResource firstCatalog = TaskResource.worldCatalog(instancePath.resolve("saves"));
        TaskResource secondCatalog = TaskResource.worldCatalog(instancePath.resolve("other-saves"));
        TaskResource iconInput = TaskResource.inputFile(firstWorldPath.resolve("icon.png"));

        assertEquals(TaskResource.Kind.GAME_WORLD, firstWorld.getKind());
        assertEquals(TaskResource.Kind.NBT_FILE, levelData.getKind());
        assertEquals(TaskResource.Kind.NBT_DIRECTORY, regionDirectory.getKind());
        assertEquals(TaskResource.Kind.WORLD_CATALOG, firstCatalog.getKind());
        assertEquals(TaskResource.Kind.INPUT_FILE, iconInput.getKind());
        assertTrue(instance.conflictsWith(firstWorld));
        assertTrue(firstWorld.conflictsWith(instance));
        assertTrue(instance.conflictsWith(firstCatalog));
        assertTrue(firstCatalog.conflictsWith(instance));
        assertTrue(firstWorld.conflictsWith(levelData));
        assertTrue(levelData.conflictsWith(firstWorld));
        assertTrue(firstWorld.conflictsWith(regionDirectory));
        assertTrue(regionDirectory.conflictsWith(firstWorld));
        assertTrue(firstWorld.conflictsWith(iconInput));
        assertTrue(iconInput.conflictsWith(firstWorld));
        assertFalse(firstWorld.conflictsWith(firstCatalog));
        assertFalse(firstCatalog.conflictsWith(firstWorld));
        assertFalse(firstCatalog.conflictsWith(secondCatalog));
        assertFalse(firstWorld.conflictsWith(secondWorld));
        assertFalse(secondWorld.conflictsWith(firstWorld));
    }

    /// Verifies normalization preserves distinct exact-file semantics at one path while still deduplicating repeats.
    @Test
    public void normalizationRetainsDistinctExactFileKindsAtSamePath() {
        Path path = temporaryDirectory.resolve("same-file-kind/example.jar");
        TaskResource addonFile = TaskResource.addonFile(path);
        TaskResource downloadTarget = TaskResource.downloadTarget(path);

        List<TaskResource> normalized = TaskResource.normalize(
                List.of(downloadTarget, addonFile, downloadTarget));

        assertEquals(List.of(addonFile, downloadTarget), normalized);
    }

    /// Verifies normalized directory coverage removes redundant child resources and yields a stable order.
    @Test
    public void normalizationMinimizesAndSortsResources() {
        TaskResource instance = TaskResource.gameInstance(temporaryDirectory.resolve("instances/example"));
        TaskResource nestedFile = TaskResource.downloadTarget(
                temporaryDirectory.resolve("instances/example/./example.jar"));
        TaskResource otherFile = TaskResource.downloadTarget(temporaryDirectory.resolve("shared/library.jar"));

        List<TaskResource> normalized = TaskResource.normalize(List.of(otherFile, nestedFile, instance, otherFile));

        assertEquals(List.of(instance, otherFile), normalized);
        assertTrue(instance.conflictsWith(nestedFile));
        assertTrue(nestedFile.conflictsWith(instance));
        assertFalse(instance.conflictsWith(otherFile));
    }

    /// Verifies overlapping directory resources conflict symmetrically and serialize in either declaration order.
    @Test
    public void overlappingDirectoryResourcesConflictSymmetrically() throws Exception {
        Path root = temporaryDirectory.resolve("directory-overlap");
        TaskResource broad = TaskResource.gameInstance(root);
        TaskResource narrow = TaskResource.gameDirectory(root.resolve("nested"));

        assertTrue(broad.conflictsWith(narrow));
        assertTrue(narrow.conflictsWith(broad));

        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResourceLockManager.Lease broadLease = manager.acquire(rootOwner(manager, broad))
                .get(5, TimeUnit.SECONDS);
        CompletableFuture<TaskResourceLockManager.Lease> narrowFuture = manager.acquire(rootOwner(manager, narrow));

        assertFalse(narrowFuture.isDone());
        broadLease.close();
        narrowFuture.get(5, TimeUnit.SECONDS).close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies normalization retains a game-directory marker when another directory kind covers the same range.
    @Test
    public void normalizationRetainsKindSensitiveGameDirectoryMarker() {
        Path repository = temporaryDirectory.resolve("semantic-repository");
        TaskResource cache = TaskResource.cache(repository);
        TaskResource gameDirectory = TaskResource.gameDirectory(repository);

        List<TaskResource> normalized = TaskResource.normalize(List.of(cache, gameDirectory));

        assertEquals(List.of(cache, gameDirectory), normalized);
        assertTrue(normalized.stream()
                .filter(resource -> resource.getKind() == TaskResource.Kind.GAME_DIRECTORY)
                .findFirst()
                .orElseThrow()
                .permitsNested(TaskResource.repositoryOperation(repository)));
    }

    /// Verifies exact-file resources cannot serve as conservative descendant boundaries.
    @Test
    public void exactFileIsNotACompleteConservativeBoundary() {
        TaskResource file = TaskResource.downloadTarget(temporaryDirectory.resolve("boundary/file.jar"));
        TaskResource directory = TaskResource.gameInstance(temporaryDirectory.resolve("boundary/instance"));

        assertFalse(file.isExclusiveCoverage());
        assertFalse(file.isCompleteBoundary());
        assertTrue(directory.isExclusiveCoverage());
        assertTrue(directory.isCompleteBoundary());
    }

    /// Verifies the public task contract publishes the same minimized and stable snapshot as the lock manager.
    @Test
    public void taskSetResourcesPublishesMinimizedStableSnapshot() {
        TaskResource instance = TaskResource.gameInstance(temporaryDirectory.resolve("instances/public"));
        TaskResource nestedFile = TaskResource.downloadTarget(
                temporaryDirectory.resolve("instances/public/nested.jar"));
        TaskResource otherFile = TaskResource.downloadTarget(temporaryDirectory.resolve("other/public.jar"));

        Task<Void> task = emptyTask().setResources(otherFile, nestedFile, instance, otherFile);

        assertEquals(List.of(instance, otherFile), List.copyOf(task.getResources()));
    }

    /// Verifies a pure orchestration owner permits independently declared filesystem children.
    @Test
    public void orchestrationOwnerAllowsPreciselyDeclaredChildren() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource childResource = TaskResource.downloadTarget(temporaryDirectory.resolve("orchestration/child.jar"));
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parent = manager.createOwner(
                execution,
                null,
                Set.of(TaskResource.orchestration()));
        TaskResourceLockManager.Lease parentLease = manager.acquire(parent).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Owner child = manager.createOwner(execution, parent, Set.of(childResource));

        TaskResourceLockManager.Lease childLease = manager.acquire(child).get(5, TimeUnit.SECONDS);

        childLease.close();
        parentLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies an orchestration marker cannot mask a non-boundary file held by an older ancestor.
    @Test
    public void orchestrationMarkerCannotAuthorizeConservativeExpansion() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource file = TaskResource.downloadTarget(temporaryDirectory.resolve("orchestration/boundary.jar"));
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner root = manager.createOwner(execution, null, Set.of(file));
        TaskResourceLockManager.Lease rootLease = manager.acquire(root).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Owner orchestration = manager.createOwner(
                execution,
                root,
                Set.of(TaskResource.orchestration()));
        TaskResourceLockManager.Lease orchestrationLease = manager.acquire(orchestration).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Owner conservative = manager.createOwner(
                execution,
                orchestration,
                Set.of(TaskResource.conservative()));

        assertThrows(CompletionException.class, () -> manager.acquire(conservative).join());
        orchestrationLease.close();
        rootLease.close();
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies repository coordination scopes preserve only the intended short and repository-wide conflicts.
    @Test
    public void repositoryScopesPreservePreciseInstanceConcurrency() {
        Path repository = temporaryDirectory.resolve("repository");
        TaskResource metadata = TaskResource.repositoryMetadata(repository);
        TaskResource operation = TaskResource.repositoryOperation(repository);
        TaskResource otherOperation = TaskResource.repositoryOperation(repository);
        TaskResource instance = TaskResource.gameInstance(repository.resolve("versions/example"));
        TaskResource sharedDirectory = TaskResource.gameDirectory(repository);
        TaskResource libraries = TaskResource.gameDirectory(repository.resolve("libraries"));
        TaskResource otherMetadata = TaskResource.repositoryMetadata(temporaryDirectory.resolve("other"));

        assertFalse(metadata.conflictsWith(instance));
        assertFalse(instance.conflictsWith(metadata));
        assertTrue(metadata.conflictsWith(sharedDirectory));
        assertFalse(metadata.conflictsWith(operation));
        assertFalse(metadata.conflictsWith(otherMetadata));
        assertFalse(operation.conflictsWith(otherOperation));
        assertTrue(operation.conflictsWith(sharedDirectory));
        assertFalse(operation.conflictsWith(libraries));
        assertTrue(operation.permitsNested(libraries));
        assertFalse(operation.permitsNested(sharedDirectory));
        assertTrue(sharedDirectory.permitsNested(operation));
        assertEquals(Set.of(operation, instance), Set.copyOf(TaskResource.normalize(List.of(operation, instance))));
    }

    /// Verifies cache operations serialize within one tree while different trees remain independent.
    @Test
    public void cacheOperationsSerializeWithinExclusiveCacheBoundary() throws Exception {
        Path cacheDirectory = temporaryDirectory.resolve("cache-operation");
        TaskResource firstOperation = TaskResource.cacheOperation(cacheDirectory);
        TaskResource secondOperation = TaskResource.cacheOperation(cacheDirectory);
        TaskResource independentOperation = TaskResource.cacheOperation(temporaryDirectory.resolve("other-cache"));
        TaskResource maintenance = TaskResource.cache(cacheDirectory);
        TaskResource exactEntry = TaskResource.downloadTarget(cacheDirectory.resolve("entry.bin"));
        TaskResource outside = TaskResource.downloadTarget(temporaryDirectory.resolve("outside-cache.bin"));

        assertTrue(firstOperation.conflictsWith(secondOperation));
        assertFalse(firstOperation.conflictsWith(independentOperation));
        assertTrue(firstOperation.conflictsWith(maintenance));
        assertTrue(maintenance.conflictsWith(firstOperation));
        assertTrue(firstOperation.conflictsWith(exactEntry));
        assertFalse(firstOperation.conflictsWith(outside));
        assertTrue(maintenance.covers(firstOperation));

        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResourceLockManager.Lease firstLease = manager.acquire(rootOwner(manager, firstOperation))
                .get(5, TimeUnit.SECONDS);
        CompletableFuture<TaskResourceLockManager.Lease> secondFuture = manager.acquire(
                rootOwner(manager, secondOperation));
        TaskResourceLockManager.Lease independentLease = manager.acquire(rootOwner(manager, independentOperation))
                .get(5, TimeUnit.SECONDS);
        CompletableFuture<TaskResourceLockManager.Lease> maintenanceFuture = manager.acquire(
                rootOwner(manager, maintenance));

        assertFalse(secondFuture.isDone());
        assertFalse(maintenanceFuture.isDone());
        independentLease.close();
        firstLease.close();
        TaskResourceLockManager.Lease secondLease = secondFuture.get(5, TimeUnit.SECONDS);
        secondLease.close();
        maintenanceFuture.get(5, TimeUnit.SECONDS).close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies an explicitly audited cache transaction may branch from a precise parent without widening it.
    @Test
    public void disjointCacheOperationMayBranchFromPreciseParent() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource instance = TaskResource.gameInstance(temporaryDirectory.resolve("instances/cache-parent"));
        TaskResource cacheOperation = TaskResource.cacheOperation(temporaryDirectory.resolve("cache-branch"));
        TaskResource outsideFile = TaskResource.downloadTarget(temporaryDirectory.resolve("outside-branch.jar"));
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parent = manager.createOwner(execution, null, Set.of(instance));
        TaskResourceLockManager.Lease parentLease = manager.acquire(parent).get(5, TimeUnit.SECONDS);

        TaskResourceLockManager.Owner cacheChild = manager.createOwner(execution, parent, Set.of(cacheOperation));
        TaskResourceLockManager.Lease cacheLease = manager.acquire(cacheChild).get(5, TimeUnit.SECONDS);
        cacheLease.close();

        TaskResourceLockManager.Owner unsafeChild = manager.createOwner(execution, parent, Set.of(outsideFile));
        assertThrows(CompletionException.class, () -> manager.acquire(unsafeChild).join());

        parentLease.close();
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies an unrelated parent key cannot hide a partially overlapping cache expansion.
    @Test
    public void cacheOperationCannotExpandAcrossPartiallyOverlappingAncestor() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        Path cacheDirectory = temporaryDirectory.resolve("overlapping-cache");
        TaskResource nestedDirectory = TaskResource.gameInstance(cacheDirectory.resolve("entries"));
        TaskResource unrelatedArchive = TaskResource.archive(temporaryDirectory.resolve("input.zip"));
        TaskResource cacheOperation = TaskResource.cacheOperation(cacheDirectory);
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parent = manager.createOwner(
                execution,
                null,
                Set.of(nestedDirectory, unrelatedArchive));
        TaskResourceLockManager.Lease parentLease = manager.acquire(parent).get(5, TimeUnit.SECONDS);

        TaskResourceLockManager.Owner child = manager.createOwner(execution, parent, Set.of(cacheOperation));
        assertThrows(CompletionException.class, () -> manager.acquire(child).join());

        parentLease.close();
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies concurrent shared repository owners cannot upgrade to the conflicting repository directory.
    @Test
    public void sharedRepositoryOwnersRejectDirectoryLockUpgrade() throws Exception {
        Path repository = temporaryDirectory.resolve("repository-upgrade");
        TaskResource operation = TaskResource.repositoryOperation(repository);
        TaskResource directory = TaskResource.gameDirectory(repository);
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResourceLockManager.Execution firstExecution = manager.createExecution();
        TaskResourceLockManager.Execution secondExecution = manager.createExecution();
        TaskResourceLockManager.Owner first = manager.createOwner(
                firstExecution,
                null,
                Set.of(operation, TaskResource.gameInstance(repository.resolve("versions/first"))));
        TaskResourceLockManager.Owner second = manager.createOwner(
                secondExecution,
                null,
                Set.of(operation, TaskResource.gameInstance(repository.resolve("versions/second"))));
        TaskResourceLockManager.Lease firstLease = manager.acquire(first).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Lease secondLease = manager.acquire(second).get(5, TimeUnit.SECONDS);

        TaskResourceLockManager.Owner firstUpgrade = manager.createOwner(firstExecution, first, Set.of(directory));
        TaskResourceLockManager.Owner secondUpgrade = manager.createOwner(secondExecution, second, Set.of(directory));

        assertThrows(CompletionException.class, () -> manager.acquire(firstUpgrade).join());
        assertThrows(CompletionException.class, () -> manager.acquire(secondUpgrade).join());
        secondLease.close();
        firstLease.close();
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies cross-instance descendants cannot deadlock two shared repository-operation roots.
    @Test
    public void crossInstanceWaitCycleFailsOneBranchAndUnblocksTheOther() throws Exception {
        Path repository = temporaryDirectory.resolve("repository-cycle");
        TaskResource operation = TaskResource.repositoryOperation(repository);
        TaskResource firstInstance = TaskResource.gameInstance(repository.resolve("versions/first"));
        TaskResource secondInstance = TaskResource.gameInstance(repository.resolve("versions/second"));
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResourceLockManager.Execution firstExecution = manager.createExecution();
        TaskResourceLockManager.Execution secondExecution = manager.createExecution();
        TaskResourceLockManager.Owner firstRoot = manager.createOwner(
                firstExecution, null, Set.of(operation, firstInstance));
        TaskResourceLockManager.Owner secondRoot = manager.createOwner(
                secondExecution, null, Set.of(operation, secondInstance));
        TaskResourceLockManager.Lease firstRootLease = manager.acquire(firstRoot).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Lease secondRootLease = manager.acquire(secondRoot).get(5, TimeUnit.SECONDS);

        TaskResourceLockManager.Owner firstChild = manager.createOwner(
                firstExecution, firstRoot, Set.of(secondInstance));
        CompletableFuture<TaskResourceLockManager.Lease> firstChildFuture = manager.acquire(firstChild);
        assertFalse(firstChildFuture.isDone());
        TaskResourceLockManager.Owner secondChild = manager.createOwner(
                secondExecution, secondRoot, Set.of(firstInstance));
        CompletableFuture<TaskResourceLockManager.Lease> secondChildFuture = manager.acquire(secondChild);

        Throwable cycleFailure = (Throwable) CompletableFuture.anyOf(
                firstChildFuture.handle((lease, failure) -> failure == null
                        ? new AssertionError("First cyclic child unexpectedly acquired its resource")
                        : failure),
                secondChildFuture.handle((lease, failure) -> failure == null
                        ? new AssertionError("Second cyclic child unexpectedly acquired its resource")
                        : failure))
                .get(5, TimeUnit.SECONDS);
        Throwable resolvedFailure = cycleFailure instanceof CompletionException && cycleFailure.getCause() != null
                ? cycleFailure.getCause()
                : cycleFailure;
        assertTrue(resolvedFailure instanceof IllegalStateException);
        assertTrue(resolvedFailure.getMessage().contains("wait cycle"));
        assertTrue(firstChildFuture.isCompletedExceptionally() ^ secondChildFuture.isCompletedExceptionally());

        if (firstChildFuture.isCompletedExceptionally()) {
            firstRootLease.close();
            secondChildFuture.get(5, TimeUnit.SECONDS).close();
            secondRootLease.close();
        } else {
            secondRootLease.close();
            firstChildFuture.get(5, TimeUnit.SECONDS).close();
            firstRootLease.close();
        }
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies two unrelated roots requesting the same exact path cannot hold it concurrently.
    @Test
    public void sameResourceIsMutuallyExclusive() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource resource = TaskResource.downloadTarget(temporaryDirectory.resolve("same.jar"));
        TaskResourceLockManager.Owner firstOwner = rootOwner(manager, resource);
        TaskResourceLockManager.Owner secondOwner = rootOwner(manager, resource);

        TaskResourceLockManager.Lease firstLease = manager.acquire(firstOwner).get(5, TimeUnit.SECONDS);
        CompletableFuture<TaskResourceLockManager.Lease> secondFuture = manager.acquire(secondOwner);

        assertFalse(secondFuture.isDone());
        firstLease.close();
        TaskResourceLockManager.Lease secondLease = secondFuture.get(5, TimeUnit.SECONDS);
        secondLease.close();

        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies roots holding different exact files are granted independently.
    @Test
    public void differentResourcesAreGrantedInParallel() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResourceLockManager.Owner firstOwner = rootOwner(
                manager,
                TaskResource.downloadTarget(temporaryDirectory.resolve("first.jar")));
        TaskResourceLockManager.Owner secondOwner = rootOwner(
                manager,
                TaskResource.downloadTarget(temporaryDirectory.resolve("second.jar")));

        TaskResourceLockManager.Lease firstLease = manager.acquire(firstOwner).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Lease secondLease = manager.acquire(secondOwner).get(5, TimeUnit.SECONDS);

        firstLease.close();
        secondLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies opposite declaration order cannot produce partial acquisition or ABBA deadlock.
    @Test
    public void reversedMultiResourceRequestsDoNotDeadlock() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource first = TaskResource.downloadTarget(temporaryDirectory.resolve("first.jar"));
        TaskResource second = TaskResource.downloadTarget(temporaryDirectory.resolve("second.jar"));
        TaskResourceLockManager.Execution firstExecution = manager.createExecution();
        TaskResourceLockManager.Execution secondExecution = manager.createExecution();
        TaskResourceLockManager.Owner firstOwner = manager.createOwner(
                firstExecution,
                null,
                Set.of(first, second));
        TaskResourceLockManager.Owner secondOwner = manager.createOwner(
                secondExecution,
                null,
                Set.of(second, first));

        TaskResourceLockManager.Lease firstLease = manager.acquire(firstOwner).get(5, TimeUnit.SECONDS);
        CompletableFuture<TaskResourceLockManager.Lease> secondFuture = manager.acquire(secondOwner);

        assertFalse(secondFuture.isDone());
        firstLease.close();
        TaskResourceLockManager.Lease secondLease = secondFuture.get(5, TimeUnit.SECONDS);
        secondLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies conflicting waiters retain FIFO order while a disjoint waiter can bypass them.
    @Test
    public void conflictingWaitersAreFifoWhileDisjointWaitersPass() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource blockedResource = TaskResource.downloadTarget(temporaryDirectory.resolve("blocked.jar"));
        TaskResource disjointResource = TaskResource.downloadTarget(temporaryDirectory.resolve("disjoint.jar"));
        TaskResourceLockManager.Lease holderLease = manager.acquire(rootOwner(manager, blockedResource))
                .get(5, TimeUnit.SECONDS);
        CompletableFuture<TaskResourceLockManager.Lease> firstBlocked = manager.acquire(
                rootOwner(manager, blockedResource));
        CompletableFuture<TaskResourceLockManager.Lease> secondBlocked = manager.acquire(
                rootOwner(manager, blockedResource));

        TaskResourceLockManager.Lease disjointLease = manager.acquire(rootOwner(manager, disjointResource))
                .get(5, TimeUnit.SECONDS);

        assertFalse(firstBlocked.isDone());
        assertFalse(secondBlocked.isDone());
        disjointLease.close();
        holderLease.close();
        TaskResourceLockManager.Lease firstLease = firstBlocked.get(5, TimeUnit.SECONDS);
        assertFalse(secondBlocked.isDone());
        firstLease.close();
        TaskResourceLockManager.Lease secondLease = secondBlocked.get(5, TimeUnit.SECONDS);
        secondLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a nested branch cannot overtake an independently blocked, conflicting waiter.
    @Test
    public void nestedBranchDoesNotBypassIndependentExternalWaiter() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource blocker = TaskResource.downloadTarget(temporaryDirectory.resolve("fifo-blocker.jar"));
        TaskResource shared = TaskResource.downloadTarget(temporaryDirectory.resolve("fifo-shared.jar"));
        TaskResource nestedMarker = TaskResource.downloadTarget(temporaryDirectory.resolve("fifo-nested-marker.jar"));

        TaskResourceLockManager.Lease blockerLease = manager.acquire(rootOwner(manager, blocker))
                .get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Execution parentExecution = manager.createExecution();
        TaskResourceLockManager.Owner parent = manager.createOwner(
                parentExecution,
                null,
                Set.of(TaskResource.orchestration()));
        TaskResourceLockManager.Lease parentLease = manager.acquire(parent).get(5, TimeUnit.SECONDS);

        TaskResourceLockManager.Execution externalExecution = manager.createExecution();
        TaskResourceLockManager.Owner external = manager.createOwner(
                externalExecution,
                null,
                Set.of(blocker, shared));
        CompletableFuture<TaskResourceLockManager.Lease> externalFuture = manager.acquire(external);

        // A prepared waiter contributes one state for the second resource; this avoids racing its identity callback.
        awaitCondition(() -> manager.trackedResourceCount() >= 3);

        TaskResourceLockManager.Owner nested = manager.createOwner(
                parentExecution,
                parent,
                Set.of(shared, nestedMarker));
        CompletableFuture<TaskResourceLockManager.Lease> nestedFuture = manager.acquire(nested);

        // The marker state is added only after this waiter has resolved and entered the FIFO queue.
        awaitCondition(() -> manager.trackedResourceCount() >= 4);
        assertFalse(nestedFuture.isDone());
        blockerLease.close();
        TaskResourceLockManager.Lease externalLease = externalFuture.get(5, TimeUnit.SECONDS);
        assertFalse(nestedFuture.isDone());

        externalLease.close();
        nestedFuture.get(5, TimeUnit.SECONDS).close();
        parentLease.close();
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a child can reenter an ancestor directory with a narrower exact resource.
    @Test
    public void childOwnerCanReenterAncestorCoverage() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource instance = TaskResource.gameInstance(temporaryDirectory.resolve("instances/example"));
        TaskResource childFile = TaskResource.downloadTarget(
                temporaryDirectory.resolve("instances/example/example.jar"));
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parentOwner = manager.createOwner(execution, null, Set.of(instance));
        TaskResourceLockManager.Lease parentLease = manager.acquire(parentOwner).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Owner childOwner = manager.createOwner(execution, parentOwner, Set.of(childFile));

        TaskResourceLockManager.Lease childLease = manager.acquire(childOwner).get(5, TimeUnit.SECONDS);

        childLease.close();
        parentLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies conservative descendants inherit their direct precise directory branch instead of the root union.
    @Test
    public void conservativeGrandchildrenRetainDisjointSiblingBranches() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource first = TaskResource.gameInstance(temporaryDirectory.resolve("instances/first"));
        TaskResource second = TaskResource.gameInstance(temporaryDirectory.resolve("instances/second"));
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner root = manager.createOwner(execution, null, Set.of(first, second));
        TaskResourceLockManager.Lease rootLease = manager.acquire(root).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Owner firstChild = manager.createOwner(execution, root, Set.of(first));
        TaskResourceLockManager.Owner secondChild = manager.createOwner(execution, root, Set.of(second));
        TaskResourceLockManager.Lease firstChildLease = manager.acquire(firstChild).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Lease secondChildLease = manager.acquire(secondChild).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Owner firstGrandchild = manager.createOwner(
                execution,
                firstChild,
                Set.of(TaskResource.conservative()));
        TaskResourceLockManager.Owner secondGrandchild = manager.createOwner(
                execution,
                secondChild,
                Set.of(TaskResource.conservative()));

        TaskResourceLockManager.Lease firstGrandchildLease = manager.acquire(firstGrandchild)
                .get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Lease secondGrandchildLease = manager.acquire(secondGrandchild)
                .get(5, TimeUnit.SECONDS);

        secondGrandchildLease.close();
        firstGrandchildLease.close();
        secondChildLease.close();
        firstChildLease.close();
        rootLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies an exact-file parent cannot make an unknown descendant appear safely bounded.
    @Test
    public void conservativeGrandchildUnderFileResourceFailsFast() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource file = TaskResource.addonFile(temporaryDirectory.resolve("mods/legacy.jar"));
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parent = manager.createOwner(execution, null, Set.of(file));
        TaskResourceLockManager.Lease parentLease = manager.acquire(parent).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Owner child = manager.createOwner(
                execution,
                parent,
                Set.of(TaskResource.conservative()));

        assertThrows(CompletionException.class, () -> manager.acquire(child).join());
        parentLease.close();
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a conservative child cannot expand an active repository-only owner to the global resource.
    @Test
    public void conservativeChildUnderOperationScopeFailsFast() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource operation = TaskResource.repositoryOperation(temporaryDirectory.resolve("repository"));
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parent = manager.createOwner(execution, null, Set.of(operation));
        TaskResourceLockManager.Lease parentLease = manager.acquire(parent).get(5, TimeUnit.SECONDS);

        TaskResourceLockManager.Owner conservativeChild = manager.createOwner(
                execution,
                parent,
                Set.of(TaskResource.conservative()));

        assertThrows(CompletionException.class, () -> manager.acquire(conservativeChild).join());
        parentLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies an unknown child cannot expand an active mixed repository-operation owner globally.
    @Test
    public void conservativeChildUnderMixedOperationAndInstanceFailsFast() throws Exception {
        assertConservativeChildFailsOutsideCompleteBoundary(
                TaskResource.repositoryOperation(temporaryDirectory.resolve("mixed-operation-instance")),
                TaskResource.gameInstance(temporaryDirectory.resolve("mixed-operation-instance/versions/example")));
    }

    /// Verifies an unknown child cannot expand an active mixed repository-metadata owner globally.
    @Test
    public void conservativeChildUnderMixedMetadataAndInstanceFailsFast() throws Exception {
        assertConservativeChildFailsOutsideCompleteBoundary(
                TaskResource.repositoryMetadata(temporaryDirectory.resolve("mixed-metadata-instance")),
                TaskResource.gameInstance(temporaryDirectory.resolve("mixed-metadata-instance/versions/example")));
    }

    /// Verifies a repository-wide game-directory boundary may safely retain a repository-operation marker.
    @Test
    public void conservativeChildRetainsRepositoryWideGameDirectoryBoundary() throws Exception {
        Path repository = temporaryDirectory.resolve("repository-wide");
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parent = manager.createOwner(
                execution,
                null,
                Set.of(TaskResource.gameDirectory(repository), TaskResource.repositoryOperation(repository)),
                true);
        TaskResourceLockManager.Lease parentLease = manager.acquire(parent).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Owner child = manager.createOwner(
                execution,
                parent,
                Set.of(TaskResource.conservative()));
        TaskResourceLockManager.Lease childLease = manager.acquire(child).get(5, TimeUnit.SECONDS);

        parentLease.close();
        TaskResourceLockManager.Lease outsideLease = manager.acquire(
                rootOwner(manager, TaskResource.downloadTarget(temporaryDirectory.resolve("outside-wide.jar"))))
                .get(5, TimeUnit.SECONDS);
        outsideLease.close();
        childLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a narrow game-directory that does not cover the repository root fails before global expansion.
    @Test
    public void conservativeChildUnderNarrowRepositoryDirectoryFailsFast() throws Exception {
        Path repository = temporaryDirectory.resolve("narrow-repository");
        assertConservativeChildFailsOutsideCompleteBoundary(
                TaskResource.repositoryOperation(repository),
                TaskResource.gameDirectory(repository.resolve("libraries")));
    }

    /// Verifies detaching a mixed repository owner does not turn its unknown child into an instance-only lock.
    @Test
    public void detachedMixedRepositoryOwnerStillFallsBackToGlobal() throws Exception {
        Path repository = temporaryDirectory.resolve("detached-mixed-repository");
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parent = manager.createOwner(
                execution,
                null,
                Set.of(
                        TaskResource.repositoryOperation(repository),
                        TaskResource.gameInstance(repository.resolve("versions/example"))),
                true);
        TaskResourceLockManager.Lease parentLease = manager.acquire(parent).get(5, TimeUnit.SECONDS);
        parentLease.close();

        TaskResourceLockManager.Owner child = manager.createOwner(
                execution,
                parent,
                Set.of(TaskResource.conservative()));
        TaskResourceLockManager.Lease childLease = manager.acquire(child).get(5, TimeUnit.SECONDS);
        CompletableFuture<TaskResourceLockManager.Lease> outside = manager.acquire(
                rootOwner(manager, TaskResource.downloadTarget(repository.resolve("outside.jar"))));

        assertFalse(outside.isDone());
        childLease.close();
        outside.get(5, TimeUnit.SECONDS).close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a repository-only child cannot cross a detached narrow ancestor before resolving its own child.
    @Test
    public void conservativeGrandchildDoesNotCrossDetachedNarrowAncestor() throws Exception {
        Path repository = temporaryDirectory.resolve("detached-narrow-repository");
        TaskResource instance = TaskResource.gameInstance(repository.resolve("versions/example"));
        TaskResource operation = TaskResource.repositoryOperation(repository);
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner narrowParent = manager.createOwner(execution, null, Set.of(instance), true);
        TaskResourceLockManager.Lease parentLease = manager.acquire(narrowParent).get(5, TimeUnit.SECONDS);
        parentLease.close();

        TaskResourceLockManager.Owner operationChild = manager.createOwner(
                execution,
                narrowParent,
                Set.of(operation));
        TaskResourceLockManager.Lease operationLease = manager.acquire(operationChild).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Owner conservativeGrandchild = manager.createOwner(
                execution,
                operationChild,
                Set.of(TaskResource.conservative()));

        assertThrows(CompletionException.class, () -> manager.acquire(conservativeGrandchild).join());
        operationLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies an active narrow owner rejects explicit global expansion instead of waiting in a parent-child cycle.
    @Test
    public void nestedGlobalExpansionIsRejected() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource instance = TaskResource.gameInstance(temporaryDirectory.resolve("instances/fallback"));
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parent = manager.createOwner(execution, null, Set.of(instance));
        TaskResourceLockManager.Lease parentLease = manager.acquire(parent).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Owner child = manager.createOwner(
                execution,
                parent,
                Set.of(TaskResource.global()));

        assertThrows(CompletionException.class, () -> manager.acquire(child).join());
        parentLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies an uncertain nested path fails rather than escalating beneath an active narrow directory owner.
    @Test
    public void uncertainNestedPathFailsBeforeGlobalWait() throws Exception {
        Path instance = Files.createDirectories(temporaryDirectory.resolve("instances/uncertain"));
        Path existingFile = Files.createFile(instance.resolve("existing.jar"));
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parent = manager.createOwner(
                execution,
                null,
                Set.of(TaskResource.gameInstance(instance)));
        TaskResourceLockManager.Lease parentLease = manager.acquire(parent).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Owner child = manager.createOwner(
                execution,
                parent,
                Set.of(TaskResource.downloadTarget(existingFile.resolve("missing.jar"))));

        assertThrows(CompletionException.class, () -> manager.acquire(child).join());
        parentLease.close();
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a reentrant owner is detached only after its final lease is released.
    @Test
    public void reentrantOwnerDetachesAfterFinalLease() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource instance = TaskResource.gameInstance(temporaryDirectory.resolve("instances/reentrant"));
        TaskResource outside = TaskResource.downloadTarget(temporaryDirectory.resolve("outside/reentrant.jar"));
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parent = manager.createOwner(execution, null, Set.of(instance), true);
        TaskResourceLockManager.Lease firstLease = manager.acquire(parent).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Lease secondLease = manager.acquire(parent).get(5, TimeUnit.SECONDS);

        firstLease.close();
        TaskResourceLockManager.Owner child = manager.createOwner(execution, parent, Set.of(outside));
        assertThrows(CompletionException.class, () -> manager.acquire(child).join());

        secondLease.close();
        TaskResourceLockManager.Lease reactivatedLease = manager.acquire(parent).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Owner reactivatedChild = manager.createOwner(execution, parent, Set.of(outside));
        assertThrows(CompletionException.class, () -> manager.acquire(reactivatedChild).join());
        reactivatedLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies sibling owners remain mutually exclusive even while their common parent is reentrant.
    @Test
    public void siblingOwnersConflictIndependently() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource instance = TaskResource.gameInstance(temporaryDirectory.resolve("instances/example"));
        TaskResource childFile = TaskResource.downloadTarget(
                temporaryDirectory.resolve("instances/example/example.jar"));
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parentOwner = manager.createOwner(execution, null, Set.of(instance));
        TaskResourceLockManager.Lease parentLease = manager.acquire(parentOwner).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Owner firstChild = manager.createOwner(execution, parentOwner, Set.of(childFile));
        TaskResourceLockManager.Owner secondChild = manager.createOwner(execution, parentOwner, Set.of(childFile));
        TaskResourceLockManager.Lease firstLease = manager.acquire(firstChild).get(5, TimeUnit.SECONDS);

        CompletableFuture<TaskResourceLockManager.Lease> secondFuture = manager.acquire(secondChild);

        assertFalse(secondFuture.isDone());
        firstLease.close();
        TaskResourceLockManager.Lease secondLease = secondFuture.get(5, TimeUnit.SECONDS);
        secondLease.close();
        parentLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a running sibling's descendant can reenter its owner without being deadlocked by another sibling.
    @Test
    public void descendantBypassesSiblingBlockedByActiveBranch() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource instance = TaskResource.gameInstance(temporaryDirectory.resolve("instances/descendant"));
        TaskResource childResource = TaskResource.downloadTarget(
                temporaryDirectory.resolve("instances/descendant/shared.jar"));
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parent = manager.createOwner(execution, null, Set.of(instance));
        TaskResourceLockManager.Lease parentLease = manager.acquire(parent).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Owner firstChild = manager.createOwner(execution, parent, Set.of(childResource));
        TaskResourceLockManager.Owner secondChild = manager.createOwner(execution, parent, Set.of(childResource));
        TaskResourceLockManager.Lease firstLease = manager.acquire(firstChild).get(5, TimeUnit.SECONDS);
        CompletableFuture<TaskResourceLockManager.Lease> secondFuture = manager.acquire(secondChild);

        TaskResourceLockManager.Owner grandchild = manager.createOwner(execution, firstChild, Set.of(childResource));
        TaskResourceLockManager.Lease grandchildLease = manager.acquire(grandchild).get(5, TimeUnit.SECONDS);

        grandchildLease.close();
        firstLease.close();
        secondFuture.get(5, TimeUnit.SECONDS).close();
        parentLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies sibling waiters retain queue order while an external root remains blocked by their common parent.
    @Test
    public void siblingWaitersRetainFifoOrder() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource instance = TaskResource.gameInstance(temporaryDirectory.resolve("instances/fifo"));
        TaskResource childResource = TaskResource.downloadTarget(
                temporaryDirectory.resolve("instances/fifo/shared.jar"));
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parent = manager.createOwner(execution, null, Set.of(instance));
        TaskResourceLockManager.Lease parentLease = manager.acquire(parent).get(5, TimeUnit.SECONDS);
        CompletableFuture<TaskResourceLockManager.Lease> external = manager.acquire(
                rootOwner(manager, childResource));
        TaskResourceLockManager.Owner first = manager.createOwner(execution, parent, Set.of(childResource));
        TaskResourceLockManager.Owner second = manager.createOwner(execution, parent, Set.of(childResource));

        CompletableFuture<TaskResourceLockManager.Lease> firstFuture = manager.acquire(first);
        CompletableFuture<TaskResourceLockManager.Lease> secondFuture = manager.acquire(second);
        TaskResourceLockManager.Lease firstLease = firstFuture.get(5, TimeUnit.SECONDS);
        assertFalse(secondFuture.isDone());

        firstLease.close();
        TaskResourceLockManager.Lease secondLease = secondFuture.get(5, TimeUnit.SECONDS);
        secondLease.close();
        assertFalse(external.isDone());
        parentLease.close();
        external.get(5, TimeUnit.SECONDS).close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies an external waiter queued after a parent cannot prevent that parent's nested owner from completing.
    @Test
    public void nestedOwnerBypassesExternalWaiterBlockedByAncestor() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource instance = TaskResource.gameInstance(temporaryDirectory.resolve("instances/example"));
        TaskResource childFile = TaskResource.downloadTarget(
                temporaryDirectory.resolve("instances/example/example.jar"));
        TaskResourceLockManager.Execution parentExecution = manager.createExecution();
        TaskResourceLockManager.Owner parentOwner = manager.createOwner(parentExecution, null, Set.of(instance));
        TaskResourceLockManager.Lease parentLease = manager.acquire(parentOwner).get(5, TimeUnit.SECONDS);
        CompletableFuture<TaskResourceLockManager.Lease> externalFuture = manager.acquire(
                rootOwner(manager, childFile));
        TaskResourceLockManager.Owner childOwner = manager.createOwner(parentExecution, parentOwner, Set.of(childFile));

        TaskResourceLockManager.Lease childLease = manager.acquire(childOwner).get(5, TimeUnit.SECONDS);

        assertFalse(externalFuture.isDone());
        childLease.close();
        parentLease.close();
        TaskResourceLockManager.Lease externalLease = externalFuture.get(5, TimeUnit.SECONDS);
        externalLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a nested declaration cannot silently expand beyond ancestor coverage.
    @Test
    public void nestedResourceOutsideAncestorCoverageIsRejected() {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parentOwner = manager.createOwner(
                execution,
                null,
                Set.of(TaskResource.gameInstance(temporaryDirectory.resolve("instances/example"))));

        TaskResourceLockManager.Owner childOwner = manager.createOwner(
                execution,
                parentOwner,
                Set.of(TaskResource.downloadTarget(temporaryDirectory.resolve("shared/library.jar"))));
        assertThrows(CompletionException.class, () -> manager.acquire(childOwner).join());
    }

    /// Verifies a nested owner cannot bypass the structured lifecycle by acquiring before its parent is active.
    @Test
    public void nestedOwnerRequiresActiveParentLease() {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource instance = TaskResource.gameInstance(
                temporaryDirectory.resolve("inactive-parent/versions/example"));
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parent = manager.createOwner(execution, null, Set.of(instance));
        TaskResourceLockManager.Owner child = manager.createOwner(
                execution,
                parent,
                Set.of(TaskResource.downloadTarget(
                        temporaryDirectory.resolve("inactive-parent/versions/example/example.jar"))));

        assertThrows(CompletionException.class, () -> manager.acquire(child).join());
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies an explicitly detached parent can hand an outside resource range to its child after release.
    @Test
    public void detachedOwnerCanHandOffToOutsideResource() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource parentResource = TaskResource.gameDirectory(temporaryDirectory.resolve("repository"));
        TaskResource childResource = TaskResource.gameInstance(
                temporaryDirectory.resolve("repository/versions/example"));
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parentOwner = manager.createOwner(
                execution,
                null,
                Set.of(parentResource),
                true);
        TaskResourceLockManager.Lease parentLease = manager.acquire(parentOwner).get(5, TimeUnit.SECONDS);

        parentLease.close();
        TaskResourceLockManager.Owner childOwner = manager.createOwner(execution, parentOwner, Set.of(childResource));
        TaskResourceLockManager.Lease childLease = manager.acquire(childOwner).get(5, TimeUnit.SECONDS);

        childLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies opposite handoff branches cannot revive detached ancestor coverage and deadlock each other.
    @Test
    public void detachedAncestorCoverageCannotBeRevivedByGrandchild() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource firstResource = TaskResource.downloadTarget(temporaryDirectory.resolve("handoff-first.jar"));
        TaskResource secondResource = TaskResource.downloadTarget(temporaryDirectory.resolve("handoff-second.jar"));
        TaskResourceLockManager.Execution firstExecution = manager.createExecution();
        TaskResourceLockManager.Execution secondExecution = manager.createExecution();
        TaskResourceLockManager.Owner firstParent = manager.createOwner(
                firstExecution,
                null,
                Set.of(firstResource),
                true);
        TaskResourceLockManager.Owner secondParent = manager.createOwner(
                secondExecution,
                null,
                Set.of(secondResource),
                true);
        TaskResourceLockManager.Lease firstParentLease = manager.acquire(firstParent).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Lease secondParentLease = manager.acquire(secondParent).get(5, TimeUnit.SECONDS);
        firstParentLease.close();
        secondParentLease.close();

        TaskResourceLockManager.Owner firstChild = manager.createOwner(
                firstExecution,
                firstParent,
                Set.of(secondResource));
        TaskResourceLockManager.Owner secondChild = manager.createOwner(
                secondExecution,
                secondParent,
                Set.of(firstResource));
        TaskResourceLockManager.Lease firstChildLease = manager.acquire(firstChild).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Lease secondChildLease = manager.acquire(secondChild).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Owner firstGrandchild = manager.createOwner(
                firstExecution,
                firstChild,
                Set.of(firstResource));
        TaskResourceLockManager.Owner secondGrandchild = manager.createOwner(
                secondExecution,
                secondChild,
                Set.of(secondResource));

        assertThrows(CompletionException.class, () -> manager.acquire(firstGrandchild).join());
        assertThrows(CompletionException.class, () -> manager.acquire(secondGrandchild).join());
        secondChildLease.close();
        firstChildLease.close();
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies cancelling an execution removes its waiter without later granting the resource.
    @Test
    public void waitingCancellationRemovesPendingNode() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource resource = TaskResource.downloadTarget(temporaryDirectory.resolve("same.jar"));
        TaskResourceLockManager.Owner holderOwner = rootOwner(manager, resource);
        TaskResourceLockManager.Lease holderLease = manager.acquire(holderOwner).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Execution waitingExecution = manager.createExecution();
        TaskResourceLockManager.Owner waitingOwner = manager.createOwner(waitingExecution, null, Set.of(resource));
        CompletableFuture<TaskResourceLockManager.Lease> waitingFuture = manager.acquire(waitingOwner);

        manager.cancel(waitingExecution);

        assertThrows(CancellationException.class, () -> waitingFuture.get(5, TimeUnit.SECONDS));
        assertEquals(0, manager.pendingWaiterCount());
        holderLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies cancelling the acquisition future itself removes an unresolved waiter before the holder releases.
    @Test
    public void directAcquisitionCancellationRemovesPendingNode() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource resource = TaskResource.downloadTarget(temporaryDirectory.resolve("direct-cancel.jar"));
        TaskResourceLockManager.Lease holderLease = manager.acquire(rootOwner(manager, resource))
                .get(5, TimeUnit.SECONDS);
        CompletableFuture<TaskResourceLockManager.Lease> waitingFuture = manager.acquire(rootOwner(manager, resource));

        assertTrue(waitingFuture.cancel(false));
        assertThrows(CancellationException.class, () -> waitingFuture.get(5, TimeUnit.SECONDS));
        assertEquals(0, manager.pendingWaiterCount());
        holderLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies explicit read declarations share a range while a writer remains blocked.
    @Test
    public void readResourcesShareButWriterWaits() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource writeResource = TaskResource.downloadTarget(temporaryDirectory.resolve("shared-read.jar"));
        TaskResource readResource = writeResource.readOnly();
        TaskResourceLockManager.Lease firstRead = manager.acquire(rootOwner(manager, readResource))
                .get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Lease secondRead = manager.acquire(rootOwner(manager, readResource))
                .get(5, TimeUnit.SECONDS);

        CompletableFuture<TaskResourceLockManager.Lease> writer = manager.acquire(rootOwner(manager, writeResource));
        assertFalse(writer.isDone());

        secondRead.close();
        assertFalse(writer.isDone());
        firstRead.close();
        writer.get(5, TimeUnit.SECONDS).close();
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a failed lease release blocks a conflicting writer until bounded residual cleanup succeeds.
    @Test
    public void failedReleaseRetainsWriteBlockUntilRetry() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource resource = TaskResource.downloadTarget(temporaryDirectory.resolve("residual.jar"));
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner owner = manager.createOwner(execution, null, Set.of(resource));
        TaskResourceLockManager.Lease lease = manager.acquire(owner).get(5, TimeUnit.SECONDS);

        Field statesField = TaskResourceLockManager.class.getDeclaredField("resourceStates");
        statesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<TaskResource, ?> states = (Map<TaskResource, ?>) statesField.get(manager);
        synchronized (manager) {
            states.remove(resource);
        }

        assertThrows(TaskResourceCleanupException.class, lease::close);
        assertEquals(List.of(resource.toString()), manager.residualResourceDescriptions(Set.of(execution)));

        CompletableFuture<TaskResourceLockManager.Lease> successor = manager.acquire(rootOwner(manager, resource));
        awaitCondition(() -> manager.pendingWaiterCount() == 1);
        assertFalse(successor.isDone());

        assertTrue(manager.retryResidualCleanup(Set.of(execution)).isEmpty());
        successor.get(5, TimeUnit.SECONDS).close();
        assertEquals(0, manager.pendingWaiterCount());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Creates one root owner in a fresh execution domain.
    private static TaskResourceLockManager.Owner rootOwner(
            TaskResourceLockManager manager,
            TaskResource resource) {
        TaskResourceLockManager.Execution execution = manager.createExecution();
        return manager.createOwner(execution, null, Set.of(resource));
    }

    /// Creates an inert task used to inspect public resource declarations.
    private static Task<Void> emptyTask() {
        return new Task<>() {
            /// Performs no work.
            @Override
            public void execute() {
            }
        };
    }

    /// Asserts that a conservative child fails beneath an active owner without one complete inherited boundary.
    ///
    /// @param firstResource first parent declaration
    /// @param secondResource second parent declaration
    private void assertConservativeChildFailsOutsideCompleteBoundary(
            TaskResource firstResource,
            TaskResource secondResource) throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner parent = manager.createOwner(
                execution,
                null,
                Set.of(firstResource, secondResource),
                true);
        TaskResourceLockManager.Lease parentLease = manager.acquire(parent).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Owner child = manager.createOwner(
                execution,
                parent,
                Set.of(TaskResource.conservative()));

        assertThrows(CompletionException.class, () -> manager.acquire(child).join());
        parentLease.close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Waits for a manager state transition without unbounded polling.
    ///
    /// @param condition state predicate
    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean satisfied = condition.getAsBoolean();
        while (!satisfied && System.nanoTime() < deadline) {
            Thread.yield();
            satisfied = condition.getAsBoolean();
        }
        assertTrue(satisfied, "Timed out waiting for task resource state");
    }
}
