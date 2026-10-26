// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.java.mojang;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.DownloadProvider;
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
                new DownloadProvider(),
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
