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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies alias canonicalization, asynchronous inspection, and the documented filesystem-identity boundary.
@NotNullByDefault
public final class TaskResourcePathIdentityTest {
    /// Temporary filesystem root used for real alias and replacement operations.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies potentially blocking canonicalization runs on the supplied executor and snapshots its declarations.
    @Test
    public void resolvesAsynchronouslyOnSuppliedExecutor() throws Exception {
        AtomicReference<@Nullable String> resolverThread = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "task-resource-path-resolver-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            ArrayList<TaskResource> mutableResources = new ArrayList<>();
            mutableResources.add(TaskResource.downloadTarget(temporaryDirectory.resolve("async-target.jar")));
            CompletableFuture<@Unmodifiable List<TaskResource>> future = TaskResourcePathIdentity.resolveAsync(
                    mutableResources,
                    command -> executor.execute(() -> {
                        resolverThread.set(Thread.currentThread().getName());
                        command.run();
                    }));
            mutableResources.clear();

            assertEquals(1, future.get(5, TimeUnit.SECONDS).size());
            assertEquals("task-resource-path-resolver-test", resolverThread.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Verifies a missing path is reconstructed below the nearest existing canonical ancestor.
    @Test
    public void reconstructsMissingSuffixBelowNearestExistingAncestor() throws Exception {
        Path existingDirectory = Files.createDirectories(temporaryDirectory.resolve("existing"));
        Path target = existingDirectory.resolve("first/second/target.jar");

        assertEquals(
                List.of(TaskResource.downloadTarget(existingDirectory.toRealPath()
                        .resolve("first/second/target.jar"))),
                TaskResourcePathIdentity.resolve(List.of(TaskResource.downloadTarget(target))));
    }

    /// Verifies a symbolic-link directory and its target resolve one missing descendant to the same identity.
    @Test
    public void followsSymbolicLinkForMissingDescendant() throws Exception {
        Path realDirectory = Files.createDirectories(temporaryDirectory.resolve("symbolic-real"));
        Path aliasDirectory = temporaryDirectory.resolve("symbolic-alias");
        try {
            Files.createSymbolicLink(aliasDirectory, realDirectory);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + unavailable.getMessage());
            return;
        }

        assertSameIdentity(
                realDirectory.resolve("missing/target.jar"),
                aliasDirectory.resolve("missing/target.jar"));
    }

    /// Verifies a final symbolic link conflicts with both its real target and its containing lexical directory.
    @Test
    public void finalSymbolicLinkRetainsTargetAndDirectoryEntryIdentities() throws Exception {
        Path repository = Files.createDirectories(temporaryDirectory.resolve("entry-repository"));
        Path realTarget = Files.writeString(temporaryDirectory.resolve("entry-target.jar"), "target");
        Path aliasTarget = repository.resolve("entry-alias.jar");
        try {
            Files.createSymbolicLink(aliasTarget, realTarget);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + unavailable.getMessage());
            return;
        }

        List<TaskResource> identities = TaskResourcePathIdentity.resolve(
                List.of(TaskResource.downloadTarget(aliasTarget)));

        assertEquals(2, identities.size());
        assertTrue(identities.contains(TaskResource.downloadTarget(realTarget.toRealPath())));
        assertTrue(identities.contains(TaskResource.downloadTarget(
                repository.toRealPath().resolve(aliasTarget.getFileName()))));

        TaskResourceLockManager manager = new TaskResourceLockManager();
        TaskResourceLockManager.Execution execution = manager.createExecution();
        TaskResourceLockManager.Owner directoryOwner = manager.createOwner(
                execution,
                null,
                Set.of(TaskResource.gameDirectory(repository)));
        TaskResourceLockManager.Lease directoryLease = manager.acquire(directoryOwner).get(5, TimeUnit.SECONDS);
        TaskResourceLockManager.Owner aliasOwner = manager.createOwner(
                manager.createExecution(),
                null,
                Set.of(TaskResource.downloadTarget(aliasTarget)));
        CompletableFuture<TaskResourceLockManager.Lease> aliasLease = manager.acquire(aliasOwner);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (manager.trackedResourceCount() < 3) {
                Thread.yield();
            }
        });
        assertFalse(aliasLease.isDone());

        directoryLease.close();
        aliasLease.get(5, TimeUnit.SECONDS).close();
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies executor arbitration serializes equivalent symbolic-link target paths after asynchronous resolution.
    @Test
    public void executorSerializesSymbolicLinkAliases() throws Exception {
        Path realDirectory = Files.createDirectories(temporaryDirectory.resolve("executor-symbolic-real"));
        Path aliasDirectory = temporaryDirectory.resolve("executor-symbolic-alias");
        try {
            Files.createSymbolicLink(aliasDirectory, realDirectory);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + unavailable.getMessage());
            return;
        }

        TaskResourceLockManager manager = new TaskResourceLockManager();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean secondRan = new AtomicBoolean();
        Task<?> first = Task.runAsync(() -> {
            firstStarted.countDown();
            assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
        }).setResources(TaskResource.downloadTarget(realDirectory.resolve("missing/target.jar")));
        Task<?> second = Task.runAsync(() -> secondRan.set(true))
                .setResources(TaskResource.downloadTarget(aliasDirectory.resolve("missing/target.jar")));

        CompletableFuture<Boolean> firstResult = CompletableFuture.supplyAsync(
                () -> new AsyncTaskExecutor(first, manager).test());
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Boolean> secondResult = CompletableFuture.supplyAsync(
                () -> new AsyncTaskExecutor(second, manager).test());
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (manager.pendingWaiterCount() != 1) {
                Thread.yield();
            }
        });

        assertFalse(secondRan.get());
        releaseFirst.countDown();

        assertTrue(firstResult.get(5, TimeUnit.SECONDS));
        assertTrue(secondResult.get(5, TimeUnit.SECONDS));
        assertTrue(secondRan.get());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a lexical parent cannot hide a symbolic-link child whose real target is outside that parent.
    @Test
    public void preservesAliasedChildBeforeCoverageMinimization() throws Exception {
        Path repository = Files.createDirectories(temporaryDirectory.resolve("lexical-repository"));
        Path external = Files.createDirectories(temporaryDirectory.resolve("lexical-external"));
        Path alias = repository.resolve("instances-alias");
        try {
            Files.createSymbolicLink(alias, external);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + unavailable.getMessage());
            return;
        }

        Path externalInstance = external.resolve("instance");
        TaskResource parentDirectory = TaskResource.gameDirectory(repository);
        TaskResource aliasedInstance = TaskResource.gameInstance(alias.resolve("instance"));
        TaskResourceLockManager manager = new TaskResourceLockManager();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean secondRan = new AtomicBoolean();
        Task<?> first = Task.runAsync(() -> {
            firstStarted.countDown();
            assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
        }).setResources(parentDirectory, aliasedInstance);
        assertEquals(Set.of(parentDirectory), first.getResources());

        CompletableFuture<Boolean> firstResult = CompletableFuture.supplyAsync(
                () -> new AsyncTaskExecutor(first, manager).test());
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

        Task<?> second = Task.runAsync(() -> secondRan.set(true))
                .setResources(TaskResource.gameInstance(externalInstance));
        CompletableFuture<Boolean> secondResult = CompletableFuture.supplyAsync(
                () -> new AsyncTaskExecutor(second, manager).test());
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (manager.pendingWaiterCount() != 1) {
                Thread.yield();
            }
        });
        assertFalse(secondRan.get());

        releaseFirst.countDown();
        assertTrue(firstResult.get(5, TimeUnit.SECONDS));
        assertTrue(secondResult.get(5, TimeUnit.SECONDS));
        assertTrue(secondRan.get());
        assertEquals(0, manager.trackedResourceCount());
    }

    /// Verifies a Windows junction and its target resolve one missing descendant to the same identity.
    @Test
    public void followsWindowsJunctionForMissingDescendant() throws Exception {
        Assumptions.assumeTrue(File.separatorChar == '\\', "Windows junction test");
        Path realDirectory = Files.createDirectories(temporaryDirectory.resolve("junction-real"));
        Path aliasDirectory = temporaryDirectory.resolve("junction-alias");
        Assumptions.assumeTrue(createWindowsJunction(aliasDirectory, realDirectory),
                "Junction creation is unavailable");

        try {
            assertSameIdentity(
                    realDirectory.resolve("missing/target.jar"),
                    aliasDirectory.resolve("missing/target.jar"));
        } finally {
            Files.deleteIfExists(aliasDirectory);
        }
    }

    /// Verifies a Windows junction root conflicts with the lexical directory containing its reparse-point entry.
    @Test
    public void windowsJunctionRetainsDirectoryEntryIdentity() throws Exception {
        Assumptions.assumeTrue(File.separatorChar == '\\', "Windows junction test");
        Path repository = Files.createDirectories(temporaryDirectory.resolve("junction-entry-repository"));
        Path realDirectory = Files.createDirectories(temporaryDirectory.resolve("junction-entry-real"));
        Path aliasDirectory = repository.resolve("junction-entry-alias");
        Assumptions.assumeTrue(createWindowsJunction(aliasDirectory, realDirectory),
                "Junction creation is unavailable");

        try {
            List<TaskResource> identities = TaskResourcePathIdentity.resolve(
                    List.of(TaskResource.gameInstance(aliasDirectory)));
            assertEquals(2, identities.size());
            assertTrue(identities.contains(TaskResource.gameInstance(realDirectory.toRealPath())));
            assertTrue(identities.contains(TaskResource.gameInstance(
                    repository.toRealPath().resolve(aliasDirectory.getFileName()))));

            TaskResourceLockManager manager = new TaskResourceLockManager();
            TaskResourceLockManager.Owner directoryOwner = manager.createOwner(
                    manager.createExecution(),
                    null,
                    Set.of(TaskResource.gameDirectory(repository)));
            TaskResourceLockManager.Lease directoryLease = manager.acquire(directoryOwner).get(5, TimeUnit.SECONDS);
            TaskResourceLockManager.Owner aliasOwner = manager.createOwner(
                    manager.createExecution(),
                    null,
                    Set.of(TaskResource.gameInstance(aliasDirectory)));
            CompletableFuture<TaskResourceLockManager.Lease> aliasLease = manager.acquire(aliasOwner);
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (manager.trackedResourceCount() < 3) {
                    Thread.yield();
                }
            });
            assertFalse(aliasLease.isDone());

            directoryLease.close();
            aliasLease.get(5, TimeUnit.SECONDS).close();
            assertEquals(0, manager.trackedResourceCount());
        } finally {
            Files.deleteIfExists(aliasDirectory);
        }
    }

    /// Verifies a replacement is detected when filesystem attributes expose an observable identity change.
    @Test
    public void detectsReplacementBetweenAttributeSnapshots() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("replaceable.txt"), "before");
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
        Files.delete(file);
        Files.writeString(file, "after replacement");
        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);

        assertFalse(TaskResourcePathIdentity.sameSnapshot(before, after));
    }

    /// Documents that portable Path canonicalization cannot merge distinct names of one hard-linked file.
    ///
    /// The resolver remains name-based here instead of pretending to provide a stable file-object identity. Native
    /// file IDs or stable open handles would be required to close this boundary across independent acquisitions.
    @Test
    public void retainsDistinctNamesForHardLinkAliases() throws Exception {
        Path original = Files.writeString(temporaryDirectory.resolve("hard-link-original.bin"), "content");
        Path alias = temporaryDirectory.resolve("hard-link-alias.bin");
        try {
            Files.createLink(alias, original);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Hard links are unavailable: " + unavailable.getMessage());
            return;
        }
        Assumptions.assumeTrue(Files.isSameFile(original, alias), "Filesystem did not create a hard link");

        List<TaskResource> originalIdentity = TaskResourcePathIdentity.resolve(
                List.of(TaskResource.downloadTarget(original)));
        List<TaskResource> aliasIdentity = TaskResourcePathIdentity.resolve(
                List.of(TaskResource.downloadTarget(alias)));

        assertNotEquals(originalIdentity, aliasIdentity);
        assertFalse(originalIdentity.get(0).conflictsWith(aliasIdentity.get(0)));
    }

    /// Verifies an impossible descendant below a regular file fails closed to the global resource.
    @Test
    public void failsClosedBelowExistingRegularFile() throws Exception {
        Path file = Files.createFile(temporaryDirectory.resolve("regular-file.bin"));

        assertEquals(
                List.of(TaskResource.global()),
                TaskResourcePathIdentity.resolve(
                        List.of(TaskResource.downloadTarget(file.resolve("impossible-child.bin")))));
    }

    /// Verifies a provider arithmetic overflow cannot escape path identity resolution or create a narrow lock.
    @Test
    public void failsClosedWhenPathProviderReportsArithmeticOverflow() {
        Path delegate = temporaryDirectory.resolve("provider-overflow-target.jar");
        AtomicBoolean absolutePathAlreadyRequested = new AtomicBoolean();
        Path overflowingPath = (Path) Proxy.newProxyInstance(
                Path.class.getClassLoader(),
                new Class<?>[]{Path.class},
                (proxy, method, arguments) -> {
                    if ("toAbsolutePath".equals(method.getName())
                            && absolutePathAlreadyRequested.getAndSet(true)) {
                        throw new ArithmeticException("simulated path length overflow");
                    }
                    Object result = method.invoke(delegate, arguments == null ? new Object[0] : arguments);
                    return result instanceof Path ? proxy : result;
                });

        assertEquals(
                List.of(TaskResource.global()),
                TaskResourcePathIdentity.resolve(
                        List.of(TaskResource.downloadTarget(overflowingPath))));
    }

    /// Asserts that a lexical alias retains the declaration of its real target alongside any alias entry.
    ///
    /// @param realTarget target below a real directory
    /// @param aliasTarget equivalent target below an alias directory
    private static void assertSameIdentity(Path realTarget, Path aliasTarget) {
        assertFalse(Files.exists(realTarget, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(aliasTarget, LinkOption.NOFOLLOW_LINKS));

        List<TaskResource> realIdentity = TaskResourcePathIdentity.resolve(
                List.of(TaskResource.downloadTarget(realTarget)));
        List<TaskResource> aliasIdentity = TaskResourcePathIdentity.resolve(
                List.of(TaskResource.downloadTarget(aliasTarget)));

        assertTrue(aliasIdentity.containsAll(realIdentity));
        assertTrue(realIdentity.stream().anyMatch(realResource ->
                aliasIdentity.stream().anyMatch(realResource::conflictsWith)));
    }

    /// Creates one Windows junction without exposing a console window.
    ///
    /// @param alias junction path to create
    /// @param target existing junction target
    /// @return whether the command completed successfully within the timeout
    private static boolean createWindowsJunction(Path alias, Path target) throws Exception {
        Process junctionCreation = new ProcessBuilder(
                "cmd.exe",
                "/d",
                "/c",
                "mklink",
                "/J",
                alias.toString(),
                target.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        boolean completed = junctionCreation.waitFor(5, TimeUnit.SECONDS);
        if (!completed) {
            junctionCreation.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
        return completed && junctionCreation.exitValue() == 0;
    }
}
