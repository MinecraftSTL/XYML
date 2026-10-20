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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskResource;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies resource handoff through the real default game-builder composition graph.
@NotNullByDefault
public final class DefaultGameBuilderResourceTest {
    /// Temporary repository root used by the resource declarations.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies instance work can expand into shared-library work and a short repository metadata commit.
    @Test
    public void builderHandsResourcesAcrossDynamicSuccessors() {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory);
        ResourceDependencyManager dependencyManager = new ResourceDependencyManager(repository);
        GameInstanceID instanceId = new GameInstanceID("example");
        GameBuilder builder = new DefaultGameBuilder(dependencyManager)
                .name(instanceId)
                .gameVersion("1.21.1");

        Task<?> task = builder.buildAsync()
                .setResources(
                        TaskResource.repositoryOperation(repository.getBaseDirectory()),
                        TaskResource.gameInstance(repository.getInstanceRoot(instanceId)));

        TaskExecutor executor = task.executor();
        assertTrue(executor.test(), () -> String.valueOf(executor.getFailure()));
    }

    /// Verifies completion checks expose a shared repository domain and retain a precise instance resource.
    @Test
    public void completionChecksDeclareMetadataAndPreciseInstanceResources() {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory);
        ResourceDependencyManager dependencyManager = new ResourceDependencyManager(repository);
        GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("example"));
        @Unmodifiable Set<TaskResource> expectedResources = Set.of(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(repository.getInstanceRoot(manifest.id())));

        assertEquals(expectedResources, dependencyManager.checkGameCompletionAsync(manifest, true).getResources());
        assertEquals(expectedResources, dependencyManager.checkPatchCompletionAsync(manifest, true).getResources());
    }

    /// Dependency manager returning one precise instance-and-library operation for the base-game stage.
    @NotNullByDefault
    private static final class ResourceDependencyManager extends DefaultDependencyManager {
        /// Repository used to construct semantic paths.
        private final DefaultGameRepository repository;

        /// Creates a manager whose external services are unused by the overridden install method.
        ///
        /// @param repository test repository
        private ResourceDependencyManager(DefaultGameRepository repository) {
            super(repository, null, null);
            this.repository = repository;
        }

        /// Returns a stopped base-game operation with the same resource shape as a real library install.
        ///
        /// @param gameVersion selected base-game version
        /// @param baseVersion destination manifest
        /// @param libraryId logical library identifier
        /// @param libraryVersion selected library version
        /// @return stopped precise operation
        @Override
        public Task<GameInstanceManifest> installLibraryAsync(
                String gameVersion,
                GameInstanceManifest baseVersion,
                String libraryId,
                String libraryVersion) {
            return Task.supplyAsync(() -> baseVersion)
                    .setResources(
                            TaskResource.gameInstance(repository.getInstanceRoot(baseVersion.id())),
                            TaskResource.gameDirectory(repository.getLibrariesDirectory(baseVersion)));
        }
    }
}
