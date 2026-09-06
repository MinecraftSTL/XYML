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
package space.minecraftstl.xyml.download.java.mojang;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.MojangDownloadProvider;
import space.minecraftstl.xyml.game.GameJavaVersion;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.platform.Platform;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies that Mojang Java installation roots are captured as precise runtime resources.
@NotNullByDefault
final class MojangJavaDownloadTaskResourceTest {
    /// Temporary root used to construct isolated runtime paths.
    @TempDir
    private Path temporaryDirectory;

    /// A runtime task reserves only its target and staging trees, allowing unrelated installations to proceed.
    @Test
    void declaresTargetAndStagingRuntimeResources() {
        Path target = temporaryDirectory.resolve("managed").resolve("runtime");
        Path staging = temporaryDirectory.resolve("managed").resolve(".staging-runtime");

        MojangJavaDownloadTask task = new MojangJavaDownloadTask(
                new MojangDownloadProvider(),
                target,
                staging,
                GameJavaVersion.JAVA_17,
                Platform.SYSTEM_PLATFORM.toString());

        assertEquals(
                Set.of(TaskResource.javaRuntime(target), TaskResource.javaRuntime(staging)),
                task.getResources());

        Task<?> metadata = task.getDependents().iterator().next();
        assertEquals(Set.of(TaskResource.Kind.ORCHESTRATION), resourceKinds(metadata));
        Task<?> selector = metadata.getDependents().iterator().next();
        assertEquals(Set.of(TaskResource.Kind.ORCHESTRATION), resourceKinds(selector));
    }

    /// Returns semantic kinds for one task declaration without exposing mutable implementation state.
    ///
    /// @param task task whose declaration is inspected
    /// @return immutable resource-kind snapshot
    private static Set<TaskResource.Kind> resourceKinds(Task<?> task) {
        return task.getResources().stream().map(TaskResource::getKind).collect(Collectors.toUnmodifiableSet());
    }
}
