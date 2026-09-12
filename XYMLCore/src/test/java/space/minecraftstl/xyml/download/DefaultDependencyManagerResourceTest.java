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
                new MojangDownloadProvider(),
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
