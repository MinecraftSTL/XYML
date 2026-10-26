// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.quilt;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies Quilt compatibility checks against resolved new-instance manifests.
@NotNullByDefault
final class QuiltInstallTaskTest {
    /// Temporary repository root used by construction-only task graphs.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies an unresolved new-instance manifest accepts the vanilla main class carried by its game patch.
    @Test
    void preExecuteAcceptsResolvedVanillaMainClass() {
        DefaultDependencyManager dependencyManager = dependencyManager();
        QuiltInstallTask task = quiltTask(
                dependencyManager,
                manifestWithMainClass(GameComponentAnalyzer.VANILLA_MAIN));

        assertDoesNotThrow(task::preExecute);
    }

    /// Verifies a resolved non-vanilla launcher is still rejected before Quilt metadata is downloaded.
    @Test
    void preExecuteRejectsResolvedNonVanillaMainClass() {
        DefaultDependencyManager dependencyManager = dependencyManager();
        QuiltInstallTask task = quiltTask(
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
        return new GameInstanceManifest(new GameInstanceID("quilt-example"))
                .withPatches(List.of(gamePatch));
    }

    /// Creates one Quilt task for the supplied unresolved manifest.
    ///
    /// @param dependencyManager repository and download services
    /// @param manifest unresolved destination manifest
    /// @return stopped Quilt installation task
    private static QuiltInstallTask quiltTask(
            DefaultDependencyManager dependencyManager,
            GameInstanceManifest manifest) {
        QuiltRemoteVersion remoteVersion = new QuiltRemoteVersion(
                GameVersionNumber.asGameVersion("1.20.1"),
                "0.27.0",
                List.of("https://example.invalid/quilt.json"));
        return new QuiltInstallTask(dependencyManager, manifest, remoteVersion);
    }
}
