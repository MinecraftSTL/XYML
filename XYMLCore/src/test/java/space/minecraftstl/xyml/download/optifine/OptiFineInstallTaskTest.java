// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.optifine;

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

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies OptiFine compatibility checks against resolved new-instance manifests.
@NotNullByDefault
final class OptiFineInstallTaskTest {
    /// Temporary repository root used by construction-only task graphs.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies an unresolved new-instance manifest accepts the vanilla main class carried by its game patch.
    @Test
    void acceptsResolvedVanillaMainClass() throws Exception {
        DefaultDependencyManager dependencyManager = dependencyManager();
        GameInstanceManifest manifest = manifestWithMainClass(GameComponentAnalyzer.VANILLA_MAIN);

        assertEquals(
                GameComponentAnalyzer.VANILLA_MAIN,
                OptiFineInstallTask.resolveCompatibleMainClass(dependencyManager, manifest));
    }

    /// Verifies a resolved non-vanilla launcher is still rejected before OptiFine patching starts.
    @Test
    void rejectsResolvedNonVanillaMainClass() {
        DefaultDependencyManager dependencyManager = dependencyManager();
        GameInstanceManifest manifest = manifestWithMainClass(
                "net.fabricmc.loader.impl.launch.knot.KnotClient");

        UnsupportedInstallationException exception = assertThrows(
                UnsupportedInstallationException.class,
                () -> OptiFineInstallTask.resolveCompatibleMainClass(dependencyManager, manifest));
        assertEquals(
                UnsupportedInstallationException.UNSUPPORTED_LAUNCH_WRAPPER,
                exception.getReason());
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
        return new GameInstanceManifest(new GameInstanceID("optifine-example"))
                .withPatches(List.of(gamePatch));
    }
}
