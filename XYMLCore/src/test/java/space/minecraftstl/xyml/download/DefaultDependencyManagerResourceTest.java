// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/// Verifies resource phase declarations on dependency-manager installation compositions.
@NotNullByDefault
final class DefaultDependencyManagerResourceTest {
    /// Temporary repository and installer root.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies local installer selection retains its archive lease without expanding a conservative node globally.
    @Test
    void unsupportedLocalInstallerPreservesFailureClassification() throws Exception {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory.resolve("repository"));
        DefaultDependencyManager dependencyManager = new DefaultDependencyManager(
                repository,
                new DownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("cache")));
        GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("example"));
        Path installer = Files.writeString(temporaryDirectory.resolve("unsupported-installer.jar"), "unsupported");

        Task<GameInstanceManifest> installation = dependencyManager.installLibraryAsync(manifest, installer);

        assertFalse(installation.getResources().contains(TaskResource.conservative()));
        assertFalse(assertTimeoutPreemptively(Duration.ofSeconds(5), installation::test));
        assertInstanceOf(DefaultDependencyManager.UnsupportedLibraryInstallerException.class,
                installation.getException());
    }
}
