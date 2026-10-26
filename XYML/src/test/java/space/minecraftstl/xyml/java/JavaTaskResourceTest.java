// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.java;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.platform.Platform;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies precise resource declarations for legacy and repository-managed Java installation tasks.
@NotNullByDefault
final class JavaTaskResourceTest {
    /// Per-test repository, runtime, and archive root.
    @TempDir
    private Path temporaryDirectory;

    /// The legacy extractor owns its exact runtime tree and input archive.
    @Test
    void legacyInstallerDeclaresRuntimeAndArchive() {
        Path runtimeDirectory = temporaryDirectory.resolve("runtime");
        Path archive = temporaryDirectory.resolve("archives/runtime.zip");

        JavaInstallTask task = new JavaInstallTask(runtimeDirectory, Map.of(), archive);

        assertEquals(
                Set.of(
                        TaskResource.javaRuntime(runtimeDirectory),
                        TaskResource.archive(archive)),
                task.getResources());
    }

    /// The repository wrapper retains runtime, archive, and manifest resources for the complete chain.
    @Test
    void repositoryInstallChainDeclaresAllPersistentPaths() {
        Path repositoryRoot = temporaryDirectory.resolve("repository");
        Path archive = temporaryDirectory.resolve("archives/runtime.zip");
        Platform platform = Platform.SYSTEM_PLATFORM;
        XYMLJavaRepository repository = new XYMLJavaRepository(repositoryRoot);

        Task<JavaRuntime> task = repository.getInstallJavaTask(
                platform,
                "runtime",
                Map.of(),
                archive);

        assertEquals(
                Set.of(
                        TaskResource.javaRuntime(repository.getJavaDir(platform, "runtime")),
                        TaskResource.archive(archive),
                        TaskResource.configuration(repository.getManifestFile(platform, "runtime"))),
                task.getResources());
    }
}
