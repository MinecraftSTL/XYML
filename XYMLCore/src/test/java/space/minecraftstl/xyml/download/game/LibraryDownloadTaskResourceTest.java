// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.DefaultCacheRepository;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.game.Artifact;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.Library;
import space.minecraftstl.xyml.task.TaskResource;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies that a library download reserves its exact publication target rather than the conservative global key.
@NotNullByDefault
final class LibraryDownloadTaskResourceTest {
    /// Temporary root used to construct isolated repository and cache fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// A library task declares the target that is used by both cache-hit copies and remote downloads.
    @Test
    void declaresExactDownloadTarget() {
        Path target = temporaryDirectory.resolve("libraries/example/library/1.0/library-1.0.jar");
        DefaultDependencyManager dependencyManager = new DefaultDependencyManager(
                new DefaultGameRepository(temporaryDirectory.resolve("repository")),
                new DownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("cache")));
        Library library = new Library(
                new Artifact("example", "library", "1.0"),
                "https://example.invalid/libraries/",
                null);

        LibraryDownloadTask task = new LibraryDownloadTask(dependencyManager, target, library);

        assertEquals(Set.of(TaskResource.downloadTarget(target)), task.getResources());
    }

    /// A queued library task rejects cache-repository retargeting before it reads or writes cache state.
    @Test
    void rejectsCacheDirectoryChange() {
        Path target = temporaryDirectory.resolve("libraries/example/library/1.0/library-1.0.jar");
        DefaultCacheRepository cacheRepository = new DefaultCacheRepository(temporaryDirectory.resolve("cache"));
        DefaultDependencyManager dependencyManager = new DefaultDependencyManager(
                new DefaultGameRepository(temporaryDirectory.resolve("repository")),
                new DownloadProvider(),
                cacheRepository);
        Library library = new Library(
                new Artifact("example", "library", "1.0"),
                "https://example.invalid/libraries/",
                null);
        LibraryDownloadTask task = new LibraryDownloadTask(dependencyManager, target, library);

        cacheRepository.changeDirectory(temporaryDirectory.resolve("other-cache"));

        assertThrows(IllegalStateException.class, task::preExecute);
    }
}
