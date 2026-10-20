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
package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies filesystem resource declarations for game-repository refresh roots.
@NotNullByDefault
public final class DefaultGameRepositoryResourceTest {
    /// Temporary repository roots used without touching a real game installation.
    @TempDir
    private Path temporaryDirectory;

    /// A refresh occupies only the complete repository root captured when the task is created.
    @Test
    public void refreshUsesCapturedGameDirectoryResource() {
        Path repositoryRoot = temporaryDirectory.resolve("repository");
        DefaultGameRepository repository = new DefaultGameRepository(repositoryRoot);

        Task<Void> refreshTask = repository.refreshAsync();

        assertEquals(Set.of(TaskResource.gameDirectory(repositoryRoot)), refreshTask.getResources());
    }

    /// A refresh created for an obsolete repository root fails before scanning the replacement root.
    @Test
    public void staleRefreshFailsBeforeExecution() {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory.resolve("original"));
        Task<Void> refreshTask = repository.refreshAsync();
        repository.setBaseDirectory(temporaryDirectory.resolve("replacement"));

        assertFalse(refreshTask.test());
        assertInstanceOf(IllegalStateException.class, refreshTask.getException());
    }

    /// A manifest save created for an obsolete root neither writes nor publishes into the replacement repository.
    @Test
    public void staleManifestSaveFailsBeforeExecution() {
        Path original = temporaryDirectory.resolve("original-save");
        Path replacement = temporaryDirectory.resolve("replacement-save");
        DefaultGameRepository repository = new DefaultGameRepository(original);
        GameInstanceID instanceId = new GameInstanceID("example");
        Task<GameInstanceManifest> save = repository.saveAsync(new GameInstanceManifest(instanceId));
        repository.setBaseDirectory(replacement);

        assertFalse(save.test());
        assertInstanceOf(IllegalStateException.class, save.getException());
        assertTrue(Files.notExists(original.resolve("versions/example/example.json")));
        assertTrue(Files.notExists(replacement.resolve("versions/example/example.json")));
        assertFalse(repository.hasInstance(instanceId));
    }

    /// A same-root catalog refresh does not make an already-created manifest save stale.
    @Test
    public void sameRootRefreshDoesNotInvalidateManifestSave() {
        Path root = temporaryDirectory.resolve("same-root-save");
        DefaultGameRepository repository = new DefaultGameRepository(root);
        GameInstanceID instanceId = new GameInstanceID("example");
        Task<GameInstanceManifest> save = repository.saveAsync(new GameInstanceManifest(instanceId));
        repository.refresh();

        assertTrue(save.test());
        assertTrue(Files.isRegularFile(root.resolve("versions/example/example.json")));
        assertTrue(repository.hasInstance(instanceId));
    }

    /// Multiple refresh tasks captured for the same root remain valid after either one rebuilds the catalog.
    @Test
    public void sameRootRefreshTasksRemainValid() {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory.resolve("same-root-refresh"));
        Task<Void> first = repository.refreshAsync();
        Task<Void> second = repository.refreshAsync();

        assertTrue(first.test());
        assertTrue(second.test());
    }
}
