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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
        assertThrows(IllegalArgumentException.class, () -> task.setResources(
                TaskResource.conservative(),
                TaskResource.global()));

        TaskResource target = TaskResource.downloadTarget(temporaryDirectory.resolve("downloads/../game.jar"));
        assertSame(task, task.setResources(target));
        assertEquals(Set.of(target), task.getResources());
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

    /// Verifies every file download declares its normalized exact destination without starting network work.
    @Test
    public void fileDownloadDeclaresExactTargetResource() {
        Path targetPath = temporaryDirectory.resolve("downloads/../game.jar");
        FileDownloadTask task = new FileDownloadTask(URI.create("https://example.invalid/game.jar"), targetPath);

        assertEquals(Set.of(TaskResource.downloadTarget(targetPath)), task.getResources());
        assertEquals(targetPath, task.getPath());
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

    /// Verifies conservative descendants inherit their direct precise branch instead of the root resource union.
    @Test
    public void conservativeGrandchildrenRetainDisjointSiblingBranches() throws Exception {
        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResource first = TaskResource.addonFile(temporaryDirectory.resolve("mods/first.jar"));
        TaskResource second = TaskResource.addonFile(temporaryDirectory.resolve("mods/second.jar"));
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

        assertThrows(IllegalStateException.class, () -> manager.createOwner(
                execution,
                parentOwner,
                Set.of(TaskResource.downloadTarget(temporaryDirectory.resolve("shared/library.jar")))));
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
}
