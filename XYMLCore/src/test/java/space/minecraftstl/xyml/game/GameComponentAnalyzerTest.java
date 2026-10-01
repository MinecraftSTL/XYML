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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies component detection, structural certainty, and modded detection for analyzed instances.
@NotNullByDefault
final class GameComponentAnalyzerTest {
    /// Stable synthetic instance identity used by every analysis.
    private static final GameInstanceID INSTANCE_ID = new GameInstanceID("game-component-analyzer-test");

    /// Reports the detected Minecraft version while keeping structural certainty tied to explicit patches.
    @Test
    void reportsDetectedGameVersion() {
        GameComponentAnalyzer analyzer = analyze(GameVersionNumber.asGameVersion("1.20.1"), manifest());

        assertTrue(analyzer.has(GameComponentType.GAME));
        assertEquals("1.20.1", analyzer.getVersion(GameComponentType.GAME));
        assertFalse(analyzer.isClear(GameComponentType.GAME));

        GameComponentAnalyzer patched = analyze(
                GameVersionNumber.asGameVersion("1.20.1"),
                manifest(patch("game", "1.20.1")));
        assertTrue(patched.isClear(GameComponentType.GAME));
    }

    /// Reports no game component when the caller could not detect a Minecraft version.
    @Test
    void skipsGameComponentForUnknownVersion() {
        GameComponentAnalyzer analyzer = analyze(GameVersionNumber.unknown(), manifest());

        assertFalse(analyzer.has(GameComponentType.GAME));
        assertNull(analyzer.getVersion(GameComponentType.GAME));
    }

    /// Reports patch-declared loaders with their patch version and clear structure.
    @Test
    void reportsPatchDeclaredLoaderAsClearComponent() {
        GameComponentAnalyzer analyzer = analyze(
                GameVersionNumber.asGameVersion("1.20.1"),
                manifest(patch("fabric", "0.15.11"), patch("optifine", "HD_U_I6")));

        assertTrue(analyzer.has(GameComponentType.FABRIC));
        assertTrue(analyzer.isClear(GameComponentType.FABRIC));
        assertEquals("0.15.11", analyzer.getVersion(GameComponentType.FABRIC));
        assertTrue(analyzer.has(GameComponentType.OPTIFINE));
        assertTrue(analyzer.getModLoaders().contains(ModLoaderType.FABRIC));
        assertFalse(analyzer.getModLoaders().contains(ModLoaderType.FORGE));
    }

    /// Reports library-detected loaders as present but structurally unclear.
    @Test
    void reportsLibraryDetectedLoaderAsUnclearComponent() {
        GameComponentAnalyzer analyzer = analyze(
                GameVersionNumber.asGameVersion("1.20.1"),
                manifestWithLibraries(library("net.fabricmc", "fabric-loader", "0.15.11")));

        assertTrue(analyzer.has(GameComponentType.FABRIC));
        assertFalse(analyzer.isClear(GameComponentType.FABRIC));
        assertEquals("0.15.11", analyzer.getVersion(GameComponentType.FABRIC));
    }

    /// Extracts the loader version from a Forge library version that embeds the Minecraft version.
    @Test
    void extractsForgeVersionFromLibraryVersion() {
        GameComponentAnalyzer analyzer = analyze(
                GameVersionNumber.asGameVersion("1.20.1"),
                manifestWithLibraries(library("net.minecraftforge", "forge", "1.20.1-47.2.0")));

        assertTrue(analyzer.has(GameComponentType.FORGE));
        assertEquals("47.2.0", analyzer.getVersion(GameComponentType.FORGE));
        assertTrue(analyzer.getModLoaders().contains(ModLoaderType.FORGE));
    }

    /// Exposes every detected mod loader type.
    @Test
    void reportsEveryDetectedModLoaderType() {
        GameComponentAnalyzer analyzer = analyze(
                GameVersionNumber.asGameVersion("1.20.1"),
                manifest(patch("neoforge", "21.1.0"), patch("fabric", "0.15.11")));

        assertTrue(analyzer.getModLoaders().contains(ModLoaderType.NEO_FORGE));
        assertTrue(analyzer.getModLoaders().contains(ModLoaderType.FABRIC));
        assertTrue(analyzer.has(ModLoaderType.NEO_FORGE));
        assertFalse(analyzer.has(ModLoaderType.QUILT));
    }

    /// Exposes the bootstraplauncher version used by the launch-manifest maintenance.
    @Test
    void exposesBootstrapLauncherVersion() {
        GameComponentAnalyzer analyzer = analyze(
                GameVersionNumber.asGameVersion("1.17.1"),
                manifestWithLibraries(library("cpw.mods", "bootstraplauncher", "0.1.16")));

        assertEquals("0.1.16", analyzer.getBootstrapVersion());
    }

    /// Detects the Forge mod launcher from the manifest or from one patch main class.
    @Test
    void detectsForgeModLauncherMainClass() {
        assertTrue(analyze(GameVersionNumber.asGameVersion("1.20.1"),
                manifest().withMainClass(GameComponentAnalyzer.MOD_LAUNCHER_MAIN)).hasForgeModLauncher());
        assertTrue(analyze(GameVersionNumber.asGameVersion("1.20.1"),
                manifest(patchWithMainClass("forge", "47.2.0", GameComponentAnalyzer.MOD_LAUNCHER_MAIN))).hasForgeModLauncher());
        assertFalse(analyze(GameVersionNumber.asGameVersion("1.20.1"), manifest()).hasForgeModLauncher());
    }

    /// Analyzes resolved views using the launch-time libraries and the standalone patches.
    @Test
    void analyzesResolvedViewsWithLaunchLibrariesAndStandalonePatches() {
        GameInstanceManifest launchManifest = manifestWithLibraries(library("net.fabricmc", "fabric-loader", "0.15.11"));
        GameInstanceManifest standaloneManifest = manifest(patch("neoforge", "21.1.0"));
        GameInstanceManifest.Resolved resolved =
                new GameInstanceManifest.Resolved(standaloneManifest, launchManifest, standaloneManifest);

        GameComponentAnalyzer analyzer =
                GameComponentAnalyzer.analyze(resolved, GameVersionNumber.asGameVersion("1.20.1"));

        assertTrue(analyzer.has(GameComponentType.FABRIC));
        assertFalse(analyzer.isClear(GameComponentType.FABRIC));
        assertTrue(analyzer.has(GameComponentType.NEO_FORGE));
        assertTrue(analyzer.isClear(GameComponentType.NEO_FORGE));
    }

    /// Treats the legacy LaunchWrapper entry point and loader main classes as modded.
    @Test
    void treatsLoaderMainClassesAsModded() {
        assertTrue(GameComponentAnalyzer.isModded(resolvedWithMainClass(GameComponentAnalyzer.LAUNCH_WRAPPER_MAIN)));
        assertTrue(GameComponentAnalyzer.isModded(resolvedWithMainClass(GameComponentAnalyzer.MOD_LAUNCHER_MAIN)));
        assertTrue(GameComponentAnalyzer.isModded(resolvedWithMainClass("net.fabricmc.loader.impl.launch.knot.KnotClient")));
        assertFalse(GameComponentAnalyzer.isModded(resolvedWithMainClass(GameComponentAnalyzer.VANILLA_MAIN)));
        assertFalse(GameComponentAnalyzer.isModded(resolvedWithMainClass(null)));
    }

    /// Rejects dependent manifests because their libraries are not self-contained.
    @Test
    void rejectsDependentManifest() {
        GameInstanceManifest dependent = manifest().withInheritsFrom(new GameInstanceID("base"));

        assertThrows(IllegalArgumentException.class,
                () -> GameComponentAnalyzer.analyze(dependent, GameVersionNumber.asGameVersion("1.20.1")));
    }

    /// Analyzes one synthetic manifest with the given Minecraft version.
    ///
    /// @param gameVersion detected Minecraft version
    /// @param manifest    manifest to analyze
    /// @return component analysis
    private static GameComponentAnalyzer analyze(GameVersionNumber gameVersion, GameInstanceManifest manifest) {
        return GameComponentAnalyzer.analyze(manifest, gameVersion);
    }

    /// Creates one resolved view whose launch manifest carries the given main class.
    ///
    /// @param mainClass main class to install, or null for an entry point-less manifest
    /// @return resolved view sharing one manifest
    private static GameInstanceManifest.Resolved resolvedWithMainClass(@Nullable String mainClass) {
        GameInstanceManifest launchManifest = manifest().withMainClass(mainClass);
        return new GameInstanceManifest.Resolved(launchManifest, launchManifest, launchManifest);
    }

    /// Creates an empty synthetic manifest, optionally carrying loader patches and libraries.
    ///
    /// @param patches   loader patches to attach
    /// @param libraries libraries to attach
    /// @return immutable synthetic manifest
    private static GameInstanceManifest manifest(GameInstancePatch... patches) {
        return new GameInstanceManifest(INSTANCE_ID).withPatches(List.of(patches));
    }

    /// Creates a synthetic manifest carrying the supplied libraries.
    ///
    /// @param libraries libraries to attach
    /// @return immutable synthetic manifest
    private static GameInstanceManifest manifestWithLibraries(Library... libraries) {
        return new GameInstanceManifest(INSTANCE_ID).withLibraries(List.of(libraries));
    }

    /// Creates one synthetic loader patch.
    ///
    /// @param id      patch identifier
    /// @param version patch version
    /// @return immutable loader patch
    private static GameInstancePatch patch(String id, String version) {
        return new GameInstancePatch(id, version, GameInstancePatch.PRIORITY_LOADER, null, null, null);
    }

    /// Creates one synthetic loader patch with a main class.
    ///
    /// @param id        patch identifier
    /// @param version   patch version
    /// @param mainClass main class installed by the patch
    /// @return immutable loader patch
    private static GameInstancePatch patchWithMainClass(String id, String version, String mainClass) {
        return new GameInstancePatch(id, version, GameInstancePatch.PRIORITY_LOADER, null, mainClass, null);
    }

    /// Creates one synthetic library.
    ///
    /// @param group library group
    /// @param name  library name
    /// @param version library version
    /// @return immutable library
    private static Library library(String group, String name, String version) {
        return new Library(new Artifact(group, name, version));
    }
}
