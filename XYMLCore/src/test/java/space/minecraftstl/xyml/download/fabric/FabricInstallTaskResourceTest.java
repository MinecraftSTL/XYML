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
import space.minecraftstl.xyml.download.UnsupportedInstallationException;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameComponentAnalyzer;
import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies Fabric installer resources and resolved-manifest compatibility checks.
@NotNullByDefault
final class FabricInstallTaskResourceTest {
    /// Temporary game repository root used by construction-only task graphs.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies Fabric metadata and Fabric API use their audited resource scopes without performing a transfer.
    @Test
    void declaresMetadataAndInstanceResources() {
        DefaultDependencyManager dependencyManager = dependencyManager();
        GameInstanceManifest manifest = manifestWithMainClass(GameComponentAnalyzer.VANILLA_MAIN);
        @Unmodifiable Set<TaskResource> expectedInstallResources = Set.of(
                TaskResource.repositoryMetadata(dependencyManager.getGameRepository().getBaseDirectory()));
        @Unmodifiable Set<TaskResource> expectedApiResources = Set.of(
                TaskResource.gameInstance(dependencyManager.getGameRepository().getInstanceRoot(manifest.id())));

        assertEquals(
                expectedInstallResources,
                fabricTask(dependencyManager, manifest).getResources());
        assertEquals(
                expectedApiResources,
                new FabricAPIInstallTask(dependencyManager, manifest, apiVersion()).getResources());
    }

    /// Verifies an unresolved new-instance manifest accepts the vanilla main class carried by its game patch.
    @Test
    void preExecuteAcceptsResolvedVanillaMainClass() {
        DefaultDependencyManager dependencyManager = dependencyManager();
        FabricInstallTask task = fabricTask(
                dependencyManager,
                manifestWithMainClass(GameComponentAnalyzer.VANILLA_MAIN));

        assertDoesNotThrow(task::preExecute);
    }

    /// Verifies a resolved non-vanilla launcher is still rejected before Fabric metadata is downloaded.
    @Test
    void preExecuteRejectsResolvedNonVanillaMainClass() {
        DefaultDependencyManager dependencyManager = dependencyManager();
        FabricInstallTask task = fabricTask(
                dependencyManager,
                manifestWithMainClass("net.minecraftforge.client.Main"));

        UnsupportedInstallationException exception = assertThrows(
                UnsupportedInstallationException.class,
                task::preExecute);
        assertEquals(UnsupportedInstallationException.FABRIC_NOT_COMPATIBLE_WITH_FORGE, exception.getReason());
    }

    /// Creates a dependency manager rooted in the current test's temporary directory.
    ///
    /// @return construction-only dependency manager
    private DefaultDependencyManager dependencyManager() {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory);
        return new DefaultDependencyManager(
                repository,
                new DownloadProvider(),
                new DefaultCacheRepository(temporaryDirectory.resolve("cache")));
    }

    /// Creates an unresolved manifest whose base-game metadata is carried by one patch.
    ///
    /// @param mainClass resolved launcher main class stored in the game patch
    /// @return unresolved manifest shaped like a new in-progress installation
    private static GameInstanceManifest manifestWithMainClass(String mainClass) {
        GameInstancePatch gamePatch = new GameInstancePatch(
                GameComponentType.GAME.getPatchId(),
                "1.20.1",
                GameInstancePatch.PRIORITY_MC,
                null,
                mainClass,
                List.of());
        return new GameInstanceManifest(new GameInstanceID("fabric-example"))
                .withPatches(List.of(gamePatch));
    }

    /// Creates one Fabric task for the supplied unresolved manifest.
    ///
    /// @param dependencyManager repository and download services
    /// @param manifest unresolved destination manifest
    /// @return stopped Fabric installation task
    private static FabricInstallTask fabricTask(
            DefaultDependencyManager dependencyManager,
            GameInstanceManifest manifest) {
        FabricRemoteVersion remoteVersion = new FabricRemoteVersion(
                GameVersionNumber.asGameVersion("1.20.1"),
                "0.15.11",
                List.of("https://example.invalid/fabric.json"));
        return new FabricInstallTask(dependencyManager, manifest, remoteVersion);
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
