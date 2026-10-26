// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.liteloader;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.DefaultCacheRepository;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.ComponentRemoteVersion;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies LiteLoader roots declare the instance and shared-library resources they mutate.
@NotNullByDefault
final class LiteLoaderInstallTaskResourceTest {
    /// Temporary game repository root used by construction-only task graphs.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies LiteLoader uses its audited instance and shared-library scopes without performing a transfer.
    @Test
    void declaresPreciseInstanceAndLibraryResources() {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory);
        DefaultDependencyManager dependencyManager = new DefaultDependencyManager(
                repository,
                new DownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("cache")));
        GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("liteloader-example"));
        @Unmodifiable Set<TaskResource> expectedResources = Set.of(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(repository.getInstanceRoot(manifest.id())));

        assertEquals(
                expectedResources,
                new LiteLoaderInstallTask(
                        dependencyManager,
                        manifest,
                        new LiteLoaderRemoteVersion(
                                GameVersionNumber.asGameVersion("1.12.2"),
                                "1.12.2-SNAPSHOT",
                                ComponentRemoteVersion.Type.RELEASE,
                                List.of("https://example.invalid/liteloader.json"),
                                "com.mumfrey.liteloader.launch.LiteLoaderTweaker",
                                List.of()))
                        .getResources());
    }
}
