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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /// Verifies a Windows junction and its target resolve one missing descendant to the same identity.
    @Test
    public void followsWindowsJunctionForMissingDescendant() throws Exception {
        Assumptions.assumeTrue(File.separatorChar == '\\', "Windows junction test");
        Path realDirectory = Files.createDirectories(temporaryDirectory.resolve("junction-real"));
        Path aliasDirectory = temporaryDirectory.resolve("junction-alias");
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
            junctionCreation.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
        Assumptions.assumeTrue(junctionCreated && junctionCreation.exitValue() == 0,
                "Junction creation is unavailable");

        try {
            assertSameIdentity(
                    realDirectory.resolve("missing/target.jar"),
                    aliasDirectory.resolve("missing/target.jar"));
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

    /// Asserts that a lexical alias and its real path resolve to one canonical declaration.
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

        assertEquals(realIdentity, aliasIdentity);
        assertTrue(realIdentity.get(0).conflictsWith(aliasIdentity.get(0)));
    }
}
