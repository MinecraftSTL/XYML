// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.quilt;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.download.DefaultCacheRepository;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies Quilt loader roots declare repository metadata and instance resources at their respective stages.
@NotNullByDefault
final class QuiltInstallTaskResourceTest {
    /// Temporary game repository root used by construction-only task graphs.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies Quilt metadata and Quilt API use their audited resource scopes without performing a transfer.
    @Test
    void declaresMetadataAndInstanceResources() {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory);
        DefaultDependencyManager dependencyManager = new DefaultDependencyManager(
                repository,
                new DownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("cache")));
        GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("quilt-example"));
        @Unmodifiable Set<TaskResource> expectedInstallResources = Set.of(
                TaskResource.repositoryMetadata(repository.getBaseDirectory()));
        @Unmodifiable Set<TaskResource> expectedApiResources = Set.of(
                TaskResource.gameInstance(repository.getInstanceRoot(manifest.id())));

        assertEquals(
                expectedInstallResources,
                new QuiltInstallTask(
                        dependencyManager,
                        manifest,
                        new QuiltRemoteVersion(GameVersionNumber.asGameVersion("1.20.1"), "0.26.3", List.of("https://example.invalid/quilt.json")))
                        .getResources());
        assertEquals(expectedApiResources, new QuiltAPIInstallTask(dependencyManager, manifest, apiVersion()).getResources());
    }

    /// Creates complete inert add-on metadata for construction-only resource testing.
    ///
    /// @return Quilt API version with an inert download descriptor
    private static QuiltAPIRemoteVersion apiVersion() {
        RemoteAddon.Version version = new RemoteAddon.Version(
                () -> RemoteAddon.Source.MODRINTH,
                "quilt-api-version",
                "quilt-api-project",
                "Quilt API",
                "11.0.0",
                Instant.EPOCH,
                RemoteAddon.VersionType.Release,
                new RemoteAddon.File(Map.of(), "https://example.invalid/quilt-api.jar", "quilt-api.jar"),
                List.of(),
                List.of("1.20.1"),
                List.of());
        return new QuiltAPIRemoteVersion(
                GameVersionNumber.asGameVersion("1.20.1"),
                "11.0.0",
                "11.0.0+1.20.1",
                Instant.EPOCH,
                version,
                List.of("https://example.invalid/quilt-api.jar"));
    }
}
