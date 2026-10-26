// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
