/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.schematic;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.CrashReportAnalyzerTest;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies stable metadata decoding from the bundled Litematic fixture.
@NotNullByDefault
public final class LitematicFileTest {
    /// Loads one classpath Litematic fixture.
    ///
    /// @param name absolute classpath resource name
    /// @return decoded metadata
    /// @throws IOException when the resource is absent or cannot be decoded
    /// @throws URISyntaxException when the resource URL cannot be converted to a path
    private static LitematicFile load(String name) throws IOException, URISyntaxException {
        @Nullable URL resource = CrashReportAnalyzerTest.class.getResource(name);
        if (resource == null) {
            throw new IOException("Resource not found: " + name);
        }
        return LitematicFile.load(Paths.get(resource.toURI()));
    }

    /// Decodes every supported fixture metadata field without exposing JavaFX geometry.
    @Test
    public void test() throws Exception {
        LitematicFile file = load("/schematics/test.litematic");
        assertEquals("刷石机一桶岩浆下推爆破8.3万每小时", file.getName());
        assertEquals("hsds", file.getAuthor());
        assertEquals("", file.getDescription());
        assertEquals(Instant.ofEpochMilli(1746443586433L), file.getTimeCreated());
        assertEquals(Instant.ofEpochMilli(1746443586433L), file.getTimeModified());
        assertEquals(1334, file.getTotalBlocks());
        assertEquals(5746, file.getTotalVolume());
        LitematicFile.EnclosingSize size = Objects.requireNonNull(file.getEnclosingSize());
        assertEquals(new LitematicFile.EnclosingSize(17, 26, 13), size);
        assertEquals(17, size.getX());
        assertEquals(26, size.getY());
        assertEquals(13, size.getZ());
        assertEquals(1, file.getRegionCount());
    }

    /// Enforces non-negative integer dimensions while permitting a zero-volume bound.
    @Test
    public void validatesEnclosingDimensions() {
        LitematicFile.EnclosingSize zero = new LitematicFile.EnclosingSize(0, 0, 0);
        assertEquals(0, zero.getX());
        assertEquals(0, zero.getY());
        assertEquals(0, zero.getZ());
        assertThrows(IllegalArgumentException.class, () -> new LitematicFile.EnclosingSize(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new LitematicFile.EnclosingSize(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new LitematicFile.EnclosingSize(0, 0, -1));
    }
}
