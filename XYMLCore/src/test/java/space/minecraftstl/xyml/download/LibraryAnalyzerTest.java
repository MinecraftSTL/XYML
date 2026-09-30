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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Verifies deterministic primary mod-loader detection for analyzed instances.
@NotNullByDefault
final class LibraryAnalyzerTest {
    /// Stable synthetic instance identity used by every analysis.
    private static final GameInstanceID INSTANCE_ID = new GameInstanceID("library-analyzer-test");

    /// Reports no loader for an instance without loader patches.
    @Test
    void reportsNoLoaderForVanillaInstance() {
        assertNull(analyze().getPrimaryModLoader());
    }

    /// Reports the only declared loader.
    @Test
    void reportsSingleDeclaredLoader() {
        assertEquals(ModLoaderType.FABRIC, analyze(patch("fabric", "0.15.11")).getPrimaryModLoader());
    }

    /// Reports the first declared loader for an instance carrying several loaders.
    @Test
    void reportsFirstLoaderInDeclarationOrder() {
        assertEquals(
                ModLoaderType.FABRIC,
                analyze(patch("quilt", "0.20.0"), patch("fabric", "0.15.11")).getPrimaryModLoader());
        assertEquals(
                ModLoaderType.FORGE,
                analyze(patch("neoforge", "21.1.0"), patch("forge", "47.2.0")).getPrimaryModLoader());
    }

    /// Analyzes one synthetic patch set.
    ///
    /// @param patches synthetic loader patches applied to the instance
    /// @return analyzed instance libraries
    private static LibraryAnalyzer analyze(GameInstancePatch... patches) {
        GameInstanceManifest manifest = new GameInstanceManifest(INSTANCE_ID)
                .withPatches(List.copyOf(Arrays.asList(patches)));
        return LibraryAnalyzer.analyze(manifest, "1.20.1");
    }

    /// Creates one synthetic loader patch.
    ///
    /// @param id patch identifier
    /// @param version patch version
    /// @return immutable loader patch
    private static GameInstancePatch patch(String id, String version) {
        return new GameInstancePatch(
                id,
                version,
                GameInstancePatch.PRIORITY_LOADER,
                null,
                null,
                null);
    }
}
