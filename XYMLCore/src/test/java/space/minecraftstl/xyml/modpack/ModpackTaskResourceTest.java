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
package space.minecraftstl.xyml.modpack;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.modpack.curse.CurseModpackProvider;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies precise archive, destination, and configuration resources for generic modpack tasks.
@NotNullByDefault
final class ModpackTaskResourceTest {
    /// Per-test archive and destination root.
    @TempDir
    private Path temporaryDirectory;

    /// Generic extraction owns the destination tree and its input archive.
    @Test
    void installTaskDeclaresArchiveAndDestination() {
        Path archive = temporaryDirectory.resolve("archives/modpack.zip");
        Path destination = temporaryDirectory.resolve("instance");

        ModpackInstallTask<Object> task = new ModpackInstallTask<>(
                archive,
                destination,
                StandardCharsets.UTF_8,
                List.of("overrides"),
                ignored -> true,
                null);

        assertEquals(
                Set.of(
                        TaskResource.gameDirectory(destination),
                        TaskResource.archive(archive)),
                task.getResources());
    }

    /// Configuration generation owns its input archive and exact destination file.
    @Test
    void instanceTaskDeclaresArchiveAndConfiguration() {
        Path archive = temporaryDirectory.resolve("archives/modpack.zip");
        Path configuration = temporaryDirectory.resolve("instance/modpack.json");

        MinecraftInstanceTask<Object> task = new MinecraftInstanceTask<>(
                archive,
                StandardCharsets.UTF_8,
                List.of("overrides"),
                new Object(),
                CurseModpackProvider.INSTANCE,
                "Example",
                null,
                configuration);

        assertEquals(
                Set.of(
                        TaskResource.archive(archive),
                        TaskResource.configuration(configuration)),
                task.getResources());
    }

    /// Retains a lexical descendant while forwarding declarations so filesystem identity resolution can inspect it.
    @Test
    void updateTaskForwardsCoveredDescendantDeclaration() {
        Path repositoryRoot = temporaryDirectory.resolve("repository");
        Path externalRoot = temporaryDirectory.resolve("external");
        Path coveredConfiguration = externalRoot.resolve("settings.json");
        Task<?> updateTask = Task.completed(null).setResources(
                TaskResource.gameDirectory(externalRoot),
                TaskResource.configuration(coveredConfiguration));
        @Unmodifiable Set<TaskResource> minimized = updateTask.getResources();
        assertEquals(Set.of(TaskResource.gameDirectory(externalRoot)), minimized);

        ModpackUpdateTask wrapper = new ModpackUpdateTask(
                new DefaultGameRepository(repositoryRoot),
                new GameInstanceID("example"),
                updateTask);

        assertTrue(wrapper.getResourceDeclarations().contains(TaskResource.configuration(coveredConfiguration)));
    }
}
