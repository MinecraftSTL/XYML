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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.library.nbt.tag.TagType;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the decimal-only scalar field codec and primitive-array edit boundary.
@NotNullByDefault
final class NBTStructuredValueCodecTest {
    /// Preserves canonical scalar text without introducing a second numeric radix.
    @Test
    void formatsAndParsesDecimalScalars() throws Exception {
        assertEquals("-1", NBTStructuredValueCodec.formatScalar(TagType.INT, "-1"));
        assertEquals("255", NBTStructuredValueCodec.parseScalar(TagType.INT, "255"));
        assertEquals("hello", NBTStructuredValueCodec.parseScalar(TagType.STRING, "hello"));
        assertThrows(IOException.class, () -> NBTStructuredValueCodec.parseScalar(TagType.INT, "0x10"));
    }

    /// Keeps primitive-array parents read-only so array elements are changed individually.
    @Test
    void refusesDirectPrimitiveArrayReplacement() {
        assertTrue(NBTStructuredValueCodec.isPrimitiveArray(TagType.BYTE_ARRAY));
        assertTrue(NBTStructuredValueCodec.isPrimitiveArray(TagType.INT_ARRAY));
        assertTrue(NBTStructuredValueCodec.isPrimitiveArray(TagType.LONG_ARRAY));
        assertThrows(IOException.class, () -> NBTStructuredValueCodec.parseScalar(
                TagType.BYTE_ARRAY, "[1, 2]"));
    }
}
