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
package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies restored Chunk Base compatibility gates and deterministic Java-platform links.
@NotNullByDefault
final class ChunkBaseWorldToolsTest {
    /// Seed and structure links retain their distinct platform and large-biomes rules.
    @Test
    void buildsCompatibleToolLinks() {
        GameVersionNumber version = GameVersionNumber.asGameVersion("1.21.1");
        assertEquals(
                URI.create("https://www.chunkbase.com/apps/seed-map#seed=-42&platform=java_1_21_lb"),
                ChunkBaseWorldTools.createUri(-42L, version, true, ChunkBaseTool.SEED_MAP));
        assertEquals(
                URI.create("https://www.chunkbase.com/apps/nether-fortress-finder#seed=9&platform=java_1_21"),
                ChunkBaseWorldTools.createUri(9L, version, true, ChunkBaseTool.NETHER_FORTRESS));
        assertThrows(
                IllegalArgumentException.class,
                () -> ChunkBaseWorldTools.createUri(
                        1L,
                        GameVersionNumber.asGameVersion("1.12.2"),
                        false,
                        ChunkBaseTool.END_CITY));
    }

    /// Visible-row compatibility remains cheap and does not reopen world NBT on the EDT.
    @Test
    void checksRecordedVersionWithoutWorldIo() {
        WorldCatalogItem supported = world("1.20.1");
        WorldCatalogItem endCityUnsupported = world("1.12.2");
        WorldCatalogItem unsupported = world("1.6.4");
        assertTrue(ChunkBaseWorldTools.supports(supported));
        assertTrue(ChunkBaseWorldTools.supportsEndCity(supported));
        assertTrue(ChunkBaseWorldTools.supports(endCityUnsupported));
        assertFalse(ChunkBaseWorldTools.supportsEndCity(endCityUnsupported));
        assertFalse(ChunkBaseWorldTools.supports(unsupported));
    }

    /// Creates one readable synthetic row with the requested recorded game version.
    ///
    /// @param gameVersion non-blank recorded game version
    /// @return readable synthetic world row
    private static WorldCatalogItem world(String gameVersion) {
        return new WorldCatalogItem(
                Path.of("build", "chunkbase", gameVersion).toAbsolutePath().normalize(),
                gameVersion,
                gameVersion,
                0L,
                gameVersion,
                false,
                null);
    }
}
