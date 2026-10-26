// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.fabric;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.download.DefaultCacheRepository;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

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
                new DownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("cache")));
        GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("fabric-example"))
                .withPatches(List.of(new GameInstancePatch(GameComponentType.GAME.getPatchId())));
        @Unmodifiable Set<TaskResource> expectedInstallResources = Set.of(
                TaskResource.repositoryMetadata(repository.getBaseDirectory()));
        @Unmodifiable Set<TaskResource> expectedApiResources = Set.of(
                TaskResource.gameInstance(repository.getInstanceRoot(manifest.id())));

        assertEquals(
                expectedInstallResources,
                new FabricInstallTask(
                        dependencyManager,
                        manifest,
                        new FabricRemoteVersion(GameVersionNumber.asGameVersion("1.20.1"), "0.15.11", List.of("https://example.invalid/fabric.json")))
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
                GameVersionNumber.asGameVersion("1.20.1"),
                "0.100.0",
                "0.100.0+1.20.1",
                Instant.EPOCH,
                version,
                List.of("https://example.invalid/fabric-api.jar"));
    }
}
