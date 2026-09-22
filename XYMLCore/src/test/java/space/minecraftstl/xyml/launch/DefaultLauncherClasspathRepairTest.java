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
package space.minecraftstl.xyml.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.Artifact;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.Library;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Tests Legacy Fabric launch-classpath conflict repair.
@NotNullByDefault
public final class DefaultLauncherClasspathRepairTest {
    /// Modular ASM replaces the conflicting monolithic artifact only for Legacy Fabric launches.
    @Test
    public void removesAsmAllWhenLegacyFabricProvidesModularAsm() {
        Library asmAll = library("org.ow2.asm", "asm-all");
        Library asm = library("org.ow2.asm", "asm");
        Library retained = library("example", "retained");
        GameInstanceManifest original = manifestWithLibraries(List.of(asmAll, asm, retained));

        GameInstanceManifest repaired = DefaultLauncher.repairLegacyFabricAsmConflict(original, true);

        assertEquals(List.of(asm, retained), repaired.getLibraries());
        assertEquals(List.of(asmAll, asm, retained), original.getLibraries());
    }

    /// Ordinary loaders and Legacy Fabric manifests without modular ASM retain their original library snapshots.
    @Test
    public void preservesManifestWhenConflictConditionsAreAbsent() {
        Library asmAll = library("org.ow2.asm", "asm-all");
        Library asm = library("org.ow2.asm", "asm");
        GameInstanceManifest ordinaryManifest = manifestWithLibraries(List.of(asmAll, asm));
        GameInstanceManifest legacyWithoutModularAsm = manifestWithLibraries(List.of(asmAll));

        assertSame(ordinaryManifest, DefaultLauncher.repairLegacyFabricAsmConflict(ordinaryManifest, false));
        assertSame(
                legacyWithoutModularAsm,
                DefaultLauncher.repairLegacyFabricAsmConflict(legacyWithoutModularAsm, true));
    }

    /// Creates a minimal immutable manifest with the requested libraries.
    ///
    /// @param libraries immutable library snapshot
    /// @return minimal manifest carrying the snapshot
    private static GameInstanceManifest manifestWithLibraries(@Unmodifiable List<Library> libraries) {
        return new GameInstanceManifest(new GameInstanceID("legacy-fabric-test")).withLibraries(libraries);
    }

    /// Creates one ordinary Maven library coordinate for a test manifest.
    ///
    /// @param group Maven group
    /// @param name Maven artifact name
    /// @return library with a stable test version
    private static Library library(String group, String name) {
        return new Library(new Artifact(group, name, "1.0.0"));
    }
}
