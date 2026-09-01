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

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
}
