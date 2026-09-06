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
package space.minecraftstl.xyml.download.fabric;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.download.DefaultCacheRepository;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.MojangDownloadProvider;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.task.TaskResource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies Fabric loader roots declare repository metadata and instance resources at their respective stages.
@NotNullByDefault
final class FabricInstallTaskResourceTest {
    /// Temporary game repository root used by construction-only task graphs.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies Fabric metadata and Fabric API use their audited resource scopes without performing a transfer.
    @Test
    void declaresMetadataAndInstanceResources() {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory);
        DefaultDependencyManager dependencyManager = new DefaultDependencyManager(
                repository,
                new MojangDownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("cache")));
        GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("fabric-example"));
        @Unmodifiable Set<TaskResource> expectedInstallResources = Set.of(
                TaskResource.repositoryMetadata(repository.getBaseDirectory()));
        @Unmodifiable Set<TaskResource> expectedApiResources = Set.of(
                TaskResource.gameInstance(repository.getInstanceRoot(manifest.id())));

        assertEquals(
                expectedInstallResources,
                new FabricInstallTask(
                        dependencyManager,
                        manifest,
                        new FabricRemoteVersion("1.20.1", "0.15.11", List.of("https://example.invalid/fabric.json")))
                        .getResources());
        assertEquals(expectedApiResources, new FabricAPIInstallTask(dependencyManager, manifest, apiVersion()).getResources());
    }

    /// Creates complete inert add-on metadata for construction-only resource testing.
    ///
    /// @return Fabric API version with an inert download descriptor
    private static FabricAPIRemoteVersion apiVersion() {
        RemoteAddon.Version version = new RemoteAddon.Version(
                () -> RemoteAddon.Source.MODRINTH,
                "fabric-api-version",
                "fabric-api-project",
                "Fabric API",
                "0.100.0",
                Instant.EPOCH,
                RemoteAddon.VersionType.Release,
                new RemoteAddon.File(Map.of(), "https://example.invalid/fabric-api.jar", "fabric-api.jar"),
                List.of(),
                List.of("1.20.1"),
                List.of());
        return new FabricAPIRemoteVersion(
                "1.20.1",
                "0.100.0",
                "0.100.0+1.20.1",
                Instant.EPOCH,
                version,
                List.of("https://example.invalid/fabric-api.jar"));
    }
}
