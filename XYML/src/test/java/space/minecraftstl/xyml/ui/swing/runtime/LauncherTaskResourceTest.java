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
package space.minecraftstl.xyml.ui.swing.runtime;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the launch factory's short construction lock and detached per-instance execution branches.
@NotNullByDefault
final class LauncherTaskResourceTest {
    /// Maximum duration for one complete concurrency assertion.
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    /// Temporary repository tree used for semantic path resources.
    @TempDir
    private Path temporaryDirectory;

    /// Confirms synchronous task construction declares repository, instance, and shared-library resources.
    @Test
    void constructionBoundaryDeclaresCompleteShortLivedResources() {
        Path repository = temporaryDirectory.resolve("repository");
        Path instance = repository.resolve("versions/one");
        Path libraries = repository.resolve("libraries");

        Task<Void> task = LauncherLaunchTaskFactory.deferLoadedTask(
                repository,
                instance,
                libraries,
                () -> Task.completed(null));

        assertEquals(
                Set.of(
                        TaskResource.Kind.REPOSITORY_METADATA,
                        TaskResource.Kind.GAME_INSTANCE,
                        TaskResource.Kind.GAME_DIRECTORY),
                task.getResources().stream().map(TaskResource::getKind).collect(java.util.stream.Collectors.toSet()));
        assertTrue(task.getResources().stream().anyMatch(resource -> repository.equals(resource.getPath())));
        assertTrue(task.getResources().stream().anyMatch(resource -> instance.equals(resource.getPath())));
        assertTrue(task.getResources().stream().anyMatch(resource -> libraries.equals(resource.getPath())));
    }

    /// Proves shared construction is serialized but detached tasks for different instances overlap at a barrier.
    @Test
    void constructionHandoffAllowsDifferentInstancesToContinueInParallel() {
        assertTimeoutPreemptively(TIMEOUT, () -> {
            Path repository = temporaryDirectory.resolve("handoff-repository");
            Path libraries = repository.resolve("libraries");
            Path firstInstance = repository.resolve("versions/first");
            Path secondInstance = repository.resolve("versions/second");
            CountDownLatch firstConstructionEntered = new CountDownLatch(1);
            CountDownLatch releaseFirstConstruction = new CountDownLatch(1);
            CountDownLatch secondConstructionEntered = new CountDownLatch(1);
            CyclicBarrier instanceBarrier = new CyclicBarrier(2);

            Task<Void> first = LauncherLaunchTaskFactory.deferLoadedTask(
                    repository,
                    firstInstance,
                    libraries,
                    () -> {
                        firstConstructionEntered.countDown();
                        await(releaseFirstConstruction);
                        return instanceTask(firstInstance, instanceBarrier);
                    });
            Task<Void> second = LauncherLaunchTaskFactory.deferLoadedTask(
                    repository,
                    secondInstance,
                    libraries,
                    () -> {
                        secondConstructionEntered.countDown();
                        return instanceTask(secondInstance, instanceBarrier);
                    });

            CompletableFuture<Boolean> firstResult = CompletableFuture.supplyAsync(first::test);
            assertTrue(firstConstructionEntered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Boolean> secondResult = CompletableFuture.supplyAsync(second::test);
            try {
                assertFalse(secondConstructionEntered.await(200, TimeUnit.MILLISECONDS));
            } finally {
                releaseFirstConstruction.countDown();
            }

            assertTrue(secondConstructionEntered.await(5, TimeUnit.SECONDS));
            assertTrue(firstResult.get(5, TimeUnit.SECONDS));
            assertTrue(secondResult.get(5, TimeUnit.SECONDS));
        });
    }

    /// Creates one exact instance task that can complete only while its sibling is running concurrently.
    ///
    /// @param instance instance directory protected by the task
    /// @param barrier two-party overlap proof
    /// @return unstarted exact instance task
    private static Task<Void> instanceTask(Path instance, CyclicBarrier barrier) {
        return Task.runAsync(() -> barrier.await(5, TimeUnit.SECONDS))
                .setResources(TaskResource.gameInstance(instance));
    }

    /// Waits for one bounded construction gate and converts timeout into an assertion failure.
    ///
    /// @param latch construction gate
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for launch construction gate");
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for launch construction gate", interruption);
        }
    }
}
