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
import space.minecraftstl.xyml.library.nbt.tag.ByteArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.IntArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.IntTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.LongArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies scalar and compact aggregate value forms used by the structured editor.
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

    /// Formats numeric Lists and primitive arrays without SNBT brackets.
    @Test
    void formatsEditableAggregatesWithoutBrackets() {
        assertTrue(NBTStructuredValueCodec.isPrimitiveArray(TagType.BYTE_ARRAY));
        assertTrue(NBTStructuredValueCodec.isPrimitiveArray(TagType.INT_ARRAY));
        assertTrue(NBTStructuredValueCodec.isPrimitiveArray(TagType.LONG_ARRAY));
        assertEquals("0, 127, -1", NBTStructuredValueCodec.formatAggregate(
                new ByteArrayTag(new byte[]{0, 127, -1})));
        assertEquals("1, -2", NBTStructuredValueCodec.formatAggregate(
                new IntArrayTag(new int[]{1, -2})));
        assertEquals("3, -4", NBTStructuredValueCodec.formatAggregate(
                new LongArrayTag(new long[]{3L, -4L})));

        ListTag<IntTag> list = new ListTag<>(TagType.INT);
        list.addTag(new IntTag(5)).addTag(new IntTag(-6));
        assertEquals("5, -6", NBTStructuredValueCodec.formatAggregate(list));
    }

    /// Parses complete aggregate drafts while retaining container and List element types.
    @Test
    void parsesEditableAggregatesAtomically() throws Exception {
        ByteArrayTag bytes = assertInstanceOf(ByteArrayTag.class,
                NBTStructuredValueCodec.parseAggregate(new ByteArrayTag(), "1, -2, 3"));
        assertArrayEquals(new byte[]{1, -2, 3}, bytes.getArray());

        ListTag<IntTag> source = new ListTag<>(TagType.INT);
        source.setName("numbers");
        ListTag<?> parsed = assertInstanceOf(ListTag.class,
                NBTStructuredValueCodec.parseAggregate(source, "4, -5"));
        assertEquals("numbers", parsed.getName());
        assertSame(TagType.INT, parsed.getElementType());
        assertEquals(4, assertInstanceOf(IntTag.class, parsed.getTag(0)).getValue());
        assertEquals(-5, assertInstanceOf(IntTag.class, parsed.getTag(1)).getValue());

        ListTag<?> empty = assertInstanceOf(ListTag.class,
                NBTStructuredValueCodec.parseAggregate(source, "  "));
        assertSame(TagType.INT, empty.getElementType());
        assertEquals(0, empty.size());
    }

    /// Rejects brackets, absent elements, and overflow without changing the detached source.
    @Test
    void rejectsInvalidAggregateDrafts() {
        ByteArrayTag source = new ByteArrayTag(new byte[]{7, 8});
        assertThrows(IOException.class, () -> NBTStructuredValueCodec.parseAggregate(source, "[1, 2]"));
        assertThrows(IOException.class, () -> NBTStructuredValueCodec.parseAggregate(source, "1,,2"));
        assertThrows(IOException.class, () -> NBTStructuredValueCodec.parseAggregate(source, "128"));
        assertArrayEquals(new byte[]{7, 8}, source.getArray());
    }
}
