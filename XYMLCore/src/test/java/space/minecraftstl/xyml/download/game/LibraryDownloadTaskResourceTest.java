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
package space.minecraftstl.xyml.download.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.DefaultCacheRepository;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.MojangDownloadProvider;
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
                new MojangDownloadProvider(),
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
                new MojangDownloadProvider(),
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
