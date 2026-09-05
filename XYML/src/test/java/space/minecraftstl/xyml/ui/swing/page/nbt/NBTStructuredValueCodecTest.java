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
        assertEquals("-1", NBTStructuredValueCodec.formatScalar(
                TagType.INT, "-1", NBTNumberRadix.DECIMAL));
        assertEquals("255", NBTStructuredValueCodec.parseScalar(
                TagType.INT, "255", NBTNumberRadix.DECIMAL));
        assertEquals("hello", NBTStructuredValueCodec.parseScalar(
                TagType.STRING, "hello", NBTNumberRadix.DECIMAL));
        assertThrows(IOException.class, () -> NBTStructuredValueCodec.parseScalar(
                TagType.INT, "0x10", NBTNumberRadix.DECIMAL));
        assertThrows(IOException.class, () -> NBTStructuredValueCodec.parseScalar(
                TagType.DOUBLE, "0x1.0p2", NBTNumberRadix.DECIMAL));
    }

    /// Formats and parses integer bit patterns and Java hexadecimal floating-point values.
    @Test
    void formatsAndParsesHexadecimalScalars() throws Exception {
        assertEquals("0xFF", NBTStructuredValueCodec.formatScalar(
                TagType.BYTE, "-1", NBTNumberRadix.HEXADECIMAL));
        assertEquals("0xFFFF", NBTStructuredValueCodec.formatScalar(
                TagType.SHORT, "-1", NBTNumberRadix.HEXADECIMAL));
        assertEquals("0xFFFFFFFF", NBTStructuredValueCodec.formatScalar(
                TagType.INT, "-1", NBTNumberRadix.HEXADECIMAL));
        assertEquals("0xFFFFFFFFFFFFFFFF", NBTStructuredValueCodec.formatScalar(
                TagType.LONG, "-1", NBTNumberRadix.HEXADECIMAL));
        assertEquals("0x1.8p0", NBTStructuredValueCodec.formatScalar(
                TagType.FLOAT, "1.5", NBTNumberRadix.HEXADECIMAL));
        assertEquals("0x1.8p0", NBTStructuredValueCodec.formatScalar(
                TagType.DOUBLE, "1.5", NBTNumberRadix.HEXADECIMAL));
        assertEquals("-1", NBTStructuredValueCodec.parseScalar(
                TagType.BYTE, "1FF", NBTNumberRadix.HEXADECIMAL));
        assertEquals("-1", NBTStructuredValueCodec.parseScalar(
                TagType.SHORT, "1FFFF", NBTNumberRadix.HEXADECIMAL));
        assertEquals("16", NBTStructuredValueCodec.parseScalar(
                TagType.INT, "0x10", NBTNumberRadix.HEXADECIMAL));
        assertEquals("-1", NBTStructuredValueCodec.parseScalar(
                TagType.LONG, "1FFFFFFFFFFFFFFFF", NBTNumberRadix.HEXADECIMAL));
        assertEquals("1.5", NBTStructuredValueCodec.parseScalar(
                TagType.FLOAT, "1.8", NBTNumberRadix.HEXADECIMAL));
        assertEquals("1.5", NBTStructuredValueCodec.parseScalar(
                TagType.DOUBLE, "1.8", NBTNumberRadix.HEXADECIMAL));
    }

    /// Formats numeric Lists and primitive arrays without SNBT brackets.
    @Test
    void formatsEditableAggregatesWithoutBrackets() {
        assertTrue(NBTStructuredValueCodec.isPrimitiveArray(TagType.BYTE_ARRAY));
        assertTrue(NBTStructuredValueCodec.isPrimitiveArray(TagType.INT_ARRAY));
        assertTrue(NBTStructuredValueCodec.isPrimitiveArray(TagType.LONG_ARRAY));
        assertEquals("0, 127, -1", NBTStructuredValueCodec.formatAggregate(
                new ByteArrayTag(new byte[]{0, 127, -1}), NBTNumberRadix.DECIMAL));
        assertEquals("1, -2", NBTStructuredValueCodec.formatAggregate(
                new IntArrayTag(new int[]{1, -2}), NBTNumberRadix.DECIMAL));
        assertEquals("3, -4", NBTStructuredValueCodec.formatAggregate(
                new LongArrayTag(new long[]{3L, -4L}), NBTNumberRadix.DECIMAL));

        ListTag<IntTag> list = new ListTag<>(TagType.INT);
        list.addTag(new IntTag(5)).addTag(new IntTag(-6));
        assertEquals("5, -6", NBTStructuredValueCodec.formatAggregate(list, NBTNumberRadix.DECIMAL));
        assertEquals("0x5, 0xFFFFFFFA", NBTStructuredValueCodec.formatAggregate(
                list, NBTNumberRadix.HEXADECIMAL));
    }

    /// Parses complete aggregate drafts while retaining container and List element types.
    @Test
    void parsesEditableAggregatesAtomically() throws Exception {
        ByteArrayTag bytes = assertInstanceOf(ByteArrayTag.class,
                NBTStructuredValueCodec.parseAggregate(
                        new ByteArrayTag(), "1, -2, 3", NBTNumberRadix.DECIMAL));
        assertArrayEquals(new byte[]{1, -2, 3}, bytes.getArray());

        ListTag<IntTag> source = new ListTag<>(TagType.INT);
        source.setName("numbers");
        ListTag<?> parsed = assertInstanceOf(ListTag.class,
                NBTStructuredValueCodec.parseAggregate(source, "4, -5", NBTNumberRadix.DECIMAL));
        assertEquals("numbers", parsed.getName());
        assertSame(TagType.INT, parsed.getElementType());
        assertEquals(4, assertInstanceOf(IntTag.class, parsed.getTag(0)).getValue());
        assertEquals(-5, assertInstanceOf(IntTag.class, parsed.getTag(1)).getValue());

        ListTag<?> empty = assertInstanceOf(ListTag.class,
                NBTStructuredValueCodec.parseAggregate(source, "  ", NBTNumberRadix.DECIMAL));
        assertSame(TagType.INT, empty.getElementType());
        assertEquals(0, empty.size());
    }

    /// Rejects brackets, absent elements, and overflow without changing the detached source.
    @Test
    void rejectsInvalidAggregateDrafts() {
        ByteArrayTag source = new ByteArrayTag(new byte[]{7, 8});
        assertThrows(IOException.class, () -> NBTStructuredValueCodec.parseAggregate(
                source, "[1, 2]", NBTNumberRadix.DECIMAL));
        assertThrows(IOException.class, () -> NBTStructuredValueCodec.parseAggregate(
                source, "1,,2", NBTNumberRadix.DECIMAL));
        assertThrows(IOException.class, () -> NBTStructuredValueCodec.parseAggregate(
                source, "128", NBTNumberRadix.DECIMAL));
        assertThrows(IOException.class, () -> NBTStructuredValueCodec.parseAggregate(
                source, "0x7F", NBTNumberRadix.DECIMAL));
        assertArrayEquals(new byte[]{7, 8}, source.getArray());
    }

    /// Applies hexadecimal mode to every element before constructing one detached aggregate.
    @Test
    void parsesHexadecimalAggregatesWithLowBitWrapping() throws Exception {
        ByteArrayTag bytes = assertInstanceOf(ByteArrayTag.class,
                NBTStructuredValueCodec.parseAggregate(
                        new ByteArrayTag(), "0, 7F, 1FF", NBTNumberRadix.HEXADECIMAL));
        assertArrayEquals(new byte[]{0, 127, -1}, bytes.getArray());
        IntArrayTag integers = assertInstanceOf(IntArrayTag.class,
                NBTStructuredValueCodec.parseAggregate(
                        new IntArrayTag(), "7FFFFFFF, 1FFFFFFFF", NBTNumberRadix.HEXADECIMAL));
        assertArrayEquals(new int[]{Integer.MAX_VALUE, -1}, integers.getArray());
        LongArrayTag longs = assertInstanceOf(LongArrayTag.class,
                NBTStructuredValueCodec.parseAggregate(
                        new LongArrayTag(), "7FFFFFFFFFFFFFFF, 1FFFFFFFFFFFFFFFF",
                        NBTNumberRadix.HEXADECIMAL));
        assertArrayEquals(new long[]{Long.MAX_VALUE, -1L}, longs.getArray());

        ListTag<IntTag> source = new ListTag<>(TagType.INT);
        ListTag<?> values = assertInstanceOf(ListTag.class,
                NBTStructuredValueCodec.parseAggregate(
                        source, "10, FFFFFFFF", NBTNumberRadix.HEXADECIMAL));
        assertEquals(16, assertInstanceOf(IntTag.class, values.getTag(0)).getValue());
        assertEquals(-1, assertInstanceOf(IntTag.class, values.getTag(1)).getValue());
    }
}
