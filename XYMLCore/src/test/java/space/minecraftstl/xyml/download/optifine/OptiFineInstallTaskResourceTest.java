// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.optifine;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.DefaultCacheRepository;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies OptiFine installation resources for remote and local installer paths.
@NotNullByDefault
final class OptiFineInstallTaskResourceTest {
    /// Temporary repository root used by construction-only resource tests.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies remote installs omit private temporary archives while local installs retain the input archive key.
    @Test
    void declaresInstanceLibraryAndOptionalArchiveResources() {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory.resolve("repository"));
        DefaultDependencyManager dependencyManager = new DefaultDependencyManager(
                repository,
                new DownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("cache")));
        GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("optifine-resource-test"));
        OptiFineRemoteVersion remote = new OptiFineRemoteVersion(
                GameVersionNumber.asGameVersion("1.20.1"),
                "HD_U_I6",
                Collections.singletonList("https://example.invalid/optifine-installer.jar"),
                false);
        Path minecraftJar = temporaryDirectory.resolve("minecraft.jar");
        Path installer = temporaryDirectory.resolve("optifine-installer.jar");
        @Unmodifiable Set<TaskResource> expectedRemote = Set.of(
                TaskResource.gameInstance(repository.getInstanceRoot(manifest.id())),
                TaskResource.gameDirectory(repository.getLibrariesDirectory(manifest)));
        @Unmodifiable Set<TaskResource> expectedLocal = Set.of(
                TaskResource.gameInstance(repository.getInstanceRoot(manifest.id())),
                TaskResource.gameDirectory(repository.getLibrariesDirectory(manifest)),
                TaskResource.archive(installer));

        assertEquals(expectedRemote, new OptiFineInstallTask(dependencyManager, manifest, remote, minecraftJar).getResources());
        assertEquals(expectedLocal, new OptiFineInstallTask(
                dependencyManager,
                manifest,
                remote,
                minecraftJar,
                installer).getResources());
    }
}
