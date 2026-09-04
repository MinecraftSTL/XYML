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
// Added by MinecraftSTL in 2026 for transactional NBT type conversion coverage.
package space.minecraftstl.xyml.library.nbt.edit;

import space.minecraftstl.xyml.library.nbt.tag.ByteArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.ByteTag;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.DoubleTag;
import space.minecraftstl.xyml.library.nbt.tag.FloatTag;
import space.minecraftstl.xyml.library.nbt.tag.IntArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.IntTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.LongArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.LongTag;
import space.minecraftstl.xyml.library.nbt.tag.ShortTag;
import space.minecraftstl.xyml.library.nbt.tag.StringTag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies generic, value-preserving, and failure-atomic NBT type conversions.
@NotNullByDefault
public final class NBTTypeConversionTest {
    /// Verifies integral scalar conversions use Java widening and low-bit narrowing semantics.
    @Test
    void convertsIntegralScalarsWithJavaWrapping() throws Exception {
        NBTEditor<CompoundTag> editor = NBTEditor.of(new CompoundTag()
                .addInt("wide", 0x1234)
                .addByte("negative", (byte) -1));
        NBTAddress wideAddress = NBTAddress.root().appendName("wide");
        NBTNode original = editor.resolve(wideAddress);
        long revision = editor.getRevision();

        NBTNode narrowed = editor.convertType(original, TagType.BYTE);
        assertEquals((byte) 0x34, ((ByteTag) editor.snapshot(narrowed)).get());
        assertEquals(revision + 1, editor.getRevision());
        assertThrows(NBTEditException.class, () -> editor.snapshot(original));

        editor.undo();
        assertEquals(0x1234, ((IntTag) editor.snapshot(wideAddress)).get());
        editor.redo();
        assertEquals((byte) 0x34, ((ByteTag) editor.snapshot(wideAddress)).get());

        NBTNode negative = editor.resolve(NBTAddress.root().appendName("negative"));
        NBTNode widened = editor.convertType(negative, TagType.LONG);
        assertEquals(-1L, ((LongTag) editor.snapshot(widened)).get());
    }

    /// Verifies numeric-to-string conversion and forgiving base-ten string parsing.
    @Test
    void convertsNumbersAndDecimalStrings() throws Exception {
        CompoundTag root = new CompoundTag()
                .addInt("number", -42)
                .addString("fraction", "258.9")
                .addString("invalid", "not a decimal")
                .addString("hex", "0x10")
                .addString("exponent", "1.25e2")
                .addString("hugeExponent", "1e999999999")
                .addString("floatOverflow", "1e9999")
                .addString("doubleOverflow", "1e999999999");
        NBTEditor<CompoundTag> editor = NBTEditor.of(root);

        NBTNode number = editor.convertType(
                NBTAddress.root().appendName("number"), TagType.STRING);
        assertEquals("-42", ((StringTag) editor.snapshot(number)).get());
        NBTNode fraction = editor.convertType(
                NBTAddress.root().appendName("fraction"), TagType.BYTE);
        assertEquals((byte) 2, ((ByteTag) editor.snapshot(fraction)).get());
        NBTNode invalid = editor.convertType(
                NBTAddress.root().appendName("invalid"), TagType.DOUBLE);
        assertEquals(0.0, ((DoubleTag) editor.snapshot(invalid)).get());
        NBTNode hex = editor.convertType(
                NBTAddress.root().appendName("hex"), TagType.INT);
        assertEquals(0, ((IntTag) editor.snapshot(hex)).get());
        NBTNode exponent = editor.convertType(
                NBTAddress.root().appendName("exponent"), TagType.SHORT);
        assertEquals((short) 125, ((ShortTag) editor.snapshot(exponent)).get());
        NBTNode hugeExponent = editor.convertType(
                NBTAddress.root().appendName("hugeExponent"), TagType.LONG);
        assertEquals(0L, ((LongTag) editor.snapshot(hugeExponent)).get());
        NBTNode floatOverflow = editor.convertType(
                NBTAddress.root().appendName("floatOverflow"), TagType.FLOAT);
        assertEquals(Float.POSITIVE_INFINITY, ((FloatTag) editor.snapshot(floatOverflow)).get());
        NBTNode doubleOverflow = editor.convertType(
                NBTAddress.root().appendName("doubleOverflow"), TagType.DOUBLE);
        assertEquals(Double.POSITIVE_INFINITY, ((DoubleTag) editor.snapshot(doubleOverflow)).get());
    }

    /// Verifies floating-point conversions exactly follow Java primitive casts.
    @Test
    void convertsFloatingPointValuesLikeJava() throws Exception {
        CompoundTag root = new CompoundTag()
                .addDouble("positive", 12.9)
                .addDouble("negative", -12.9)
                .addDouble("nan", Double.NaN)
                .addInt("integer", 16_777_217);
        NBTEditor<CompoundTag> editor = NBTEditor.of(root);

        IntTag positive = (IntTag) editor.snapshot(editor.convertType(
                NBTAddress.root().appendName("positive"), TagType.INT));
        LongTag negative = (LongTag) editor.snapshot(editor.convertType(
                NBTAddress.root().appendName("negative"), TagType.LONG));
        ByteTag nan = (ByteTag) editor.snapshot(editor.convertType(
                NBTAddress.root().appendName("nan"), TagType.BYTE));
        FloatTag integer = (FloatTag) editor.snapshot(editor.convertType(
                NBTAddress.root().appendName("integer"), TagType.FLOAT));

        assertEquals((int) 12.9, positive.get());
        assertEquals((long) -12.9, negative.get());
        assertEquals((byte) Double.NaN, nan.get());
        assertEquals((float) 16_777_217, integer.get());
    }

    /// Verifies scalar and array conversions pack big-endian data and retain low bits on overflow.
    @Test
    void convertsIntegralArraysAsBigEndianBits() throws Exception {
        CompoundTag root = new CompoundTag()
                .addInt("scalar", 0x12345678)
                .addLong("scalarLong", 0x0102030405060708L)
                .addTag("bytes", new ByteArrayTag(new byte[]{1, 2, 3, 4, 5}))
                .addTag("ints", new IntArrayTag(new int[]{0x01020304, -1}))
                .addTag("longs", new LongArrayTag(new long[]{0x0102030405060708L}));
        NBTEditor<CompoundTag> editor = NBTEditor.of(root);

        ByteArrayTag scalarBytes = (ByteArrayTag) editor.snapshot(editor.convertType(
                NBTAddress.root().appendName("scalar"), TagType.BYTE_ARRAY));
        assertArrayEquals(new byte[]{0x12, 0x34, 0x56, 0x78}, bytes(scalarBytes));
        IntTag scalarAgain = (IntTag) editor.snapshot(editor.convertType(
                NBTAddress.root().appendName("scalar"), TagType.INT));
        assertEquals(0x12345678, scalarAgain.get());

        IntArrayTag packed = (IntArrayTag) editor.snapshot(editor.convertType(
                NBTAddress.root().appendName("bytes"), TagType.INT_ARRAY));
        assertArrayEquals(new int[]{1, 0x02030405}, ints(packed));
        IntTag lowBits = (IntTag) editor.snapshot(editor.convertType(
                NBTAddress.root().appendName("bytes"), TagType.INT));
        assertEquals(0x02030405, lowBits.get());

        IntArrayTag splitLong = (IntArrayTag) editor.snapshot(editor.convertType(
                NBTAddress.root().appendName("scalarLong"), TagType.INT_ARRAY));
        assertArrayEquals(new int[]{0x01020304, 0x05060708}, ints(splitLong));
        LongTag joinedLong = (LongTag) editor.snapshot(editor.convertType(
                NBTAddress.root().appendName("scalarLong"), TagType.LONG));
        assertEquals(0x0102030405060708L, joinedLong.get());

        LongArrayTag packedLongs = (LongArrayTag) editor.snapshot(editor.convertType(
                NBTAddress.root().appendName("ints"), TagType.LONG_ARRAY));
        assertArrayEquals(new long[]{0x01020304FFFFFFFFL}, longs(packedLongs));
        IntArrayTag intsAgain = (IntArrayTag) editor.snapshot(editor.convertType(
                NBTAddress.root().appendName("ints"), TagType.INT_ARRAY));
        assertArrayEquals(new int[]{0x01020304, -1}, ints(intsAgain));

        ByteArrayTag longBytes = (ByteArrayTag) editor.snapshot(editor.convertType(
                NBTAddress.root().appendName("longs"), TagType.BYTE_ARRAY));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, bytes(longBytes));
    }

    /// Verifies that an integral array keeps the exact big-endian byte stream at partial widths.
    @Test
    void preservesPartialWidthArrayBytesWithoutSignGuessing() throws Exception {
        NBTEditor<CompoundTag> editor = NBTEditor.of(new CompoundTag()
                .addTag("bytes", new ByteArrayTag(new byte[]{-1, 0x01}))
                .addTag("shortBytes", new ByteArrayTag(new byte[]{(byte) 0x80})));

        IntArrayTag packed = (IntArrayTag) editor.snapshot(editor.convertType(
                NBTAddress.root().appendName("bytes"), TagType.INT_ARRAY));
        assertArrayEquals(new int[]{0x0000FF01}, ints(packed));

        LongArrayTag wide = (LongArrayTag) editor.snapshot(editor.convertType(
                NBTAddress.root().appendName("shortBytes"), TagType.LONG_ARRAY));
        assertArrayEquals(new long[]{0x0000000000000080L}, longs(wide));
    }

    /// Verifies List-to-Compound conversion uses decimal indexes and remains undoable.
    @Test
    void convertsListToIndexedCompoundAndBack() throws Exception {
        ListTag<IntTag> values = new ListTag<>(TagType.INT);
        values.addTag(new IntTag(10));
        values.addTag(new IntTag(20));
        NBTEditor<CompoundTag> editor = NBTEditor.of(new CompoundTag()
                .addInt("before", 1)
                .addTag("values", values)
                .addInt("after", 2));
        NBTAddress address = NBTAddress.root().appendName("values");

        CompoundTag compound = (CompoundTag) editor.snapshot(editor.convertType(address, TagType.COMPOUND));
        assertEquals(10, compound.getInt("0"));
        assertEquals(20, compound.getInt("1"));
        assertEquals(List.of("0", "1"), List.of(compound.getTag(0).getName(), compound.getTag(1).getName()));
        assertEquals("values", compound.getName());
        assertEquals(List.of("before", "values", "after"), editor.getChildren(editor.getRootNode()).stream()
                .map(NBTNode::getName)
                .toList());

        editor.undo();
        assertTrue(editor.snapshot(address) instanceof ListTag<?>);
        editor.redo();
        NBTNode listNode = editor.convertType(address, TagType.LIST);
        ListTag<?> list = (ListTag<?>) editor.snapshot(listNode);
        assertEquals(10, ((IntTag) list.getTag(0)).get());
        assertEquals(20, ((IntTag) list.getTag(1)).get());
    }

    /// Verifies numeric map keys determine List order rather than Compound insertion order.
    @Test
    void ordersIndexedCompoundByNumericKey() throws Exception {
        CompoundTag indexed = new CompoundTag().addInt("1", 20).addInt("0", 10);
        NBTEditor<CompoundTag> editor = NBTEditor.of(new CompoundTag().addTag("indexed", indexed));

        ListTag<?> list = (ListTag<?>) editor.snapshot(editor.convertType(
                NBTAddress.root().appendName("indexed"), TagType.LIST));

        assertEquals(10, ((IntTag) list.getTag(0)).get());
        assertEquals(20, ((IntTag) list.getTag(1)).get());
    }

    /// Verifies invalid Compound/List shapes fail without revision, history, or tree changes.
    @Test
    void rejectsInvalidContainerConversionsAtomically() throws Exception {
        CompoundTag gap = new CompoundTag().addInt("0", 10).addInt("2", 20);
        CompoundTag mixed = new CompoundTag().addInt("0", 10).addString("1", "20");
        CompoundTag leadingZero = new CompoundTag().addInt("00", 10);
        ListTag<IntTag> list = new ListTag<>(TagType.INT);
        list.addTag(new IntTag(1));
        NBTEditor<CompoundTag> editor = NBTEditor.of(new CompoundTag()
                .addTag("gap", gap)
                .addTag("mixed", mixed)
                .addTag("leadingZero", leadingZero)
                .addTag("list", list));
        CompoundTag before = editor.snapshot();
        long revision = editor.getRevision();

        assertRejected(editor, "gap", TagType.LIST);
        assertRejected(editor, "mixed", TagType.LIST);
        assertRejected(editor, "leadingZero", TagType.LIST);
        assertRejected(editor, "gap", TagType.STRING);
        assertRejected(editor, "list", TagType.STRING);

        assertEquals(revision, editor.getRevision());
        assertEquals(before, editor.snapshot());
        assertFalse(editor.isDirty());
        assertFalse(editor.canUndo());
    }

    /// Verifies fixed-type parents expose and accept only their existing child type.
    @Test
    void respectsListAndArrayParentTypes() throws Exception {
        ListTag<IntTag> list = new ListTag<>(TagType.INT);
        list.addTag(new IntTag(1));
        NBTEditor<CompoundTag> editor = NBTEditor.of(new CompoundTag()
                .addTag("list", list)
                .addTag("array", new ByteArrayTag(new byte[]{1})));
        NBTNode listChild = editor.resolve(NBTAddress.root().appendName("list").appendIndex(0));
        NBTNode arrayChild = editor.resolve(NBTAddress.root().appendName("array").appendIndex(0));
        long revision = editor.getRevision();

        assertEquals(List.of(TagType.INT), editor.getConvertibleTypes(listChild));
        assertEquals(List.of(TagType.BYTE), editor.getConvertibleTypes(arrayChild));
        NBTEditException listFailure = assertThrows(NBTEditException.class,
                () -> editor.convertType(listChild, TagType.LONG));
        NBTEditException arrayFailure = assertThrows(NBTEditException.class,
                () -> editor.convertType(arrayChild, TagType.INT));

        assertEquals(NBTEditException.Reason.TYPE_MISMATCH, listFailure.reason());
        assertEquals(NBTEditException.Reason.TYPE_MISMATCH, arrayFailure.reason());
        assertEquals(revision, editor.getRevision());
    }

    /// Verifies root type changes are rejected while selecting the existing type remains a no-op.
    @Test
    void keepsTheRootTypeContract() throws Exception {
        NBTEditor<CompoundTag> editor = NBTEditor.of(new CompoundTag().addInt("0", 1));
        NBTNode root = editor.getRootNode();

        assertEquals(List.of(TagType.COMPOUND), editor.getConvertibleTypes(root));
        assertEquals(root, editor.convertType(root, TagType.COMPOUND));
        assertEquals(0L, editor.getRevision());
        NBTEditException failure = assertThrows(NBTEditException.class,
                () -> editor.convertType(root, TagType.LIST));
        assertEquals(NBTEditException.Reason.ROOT_OPERATION, failure.reason());
        assertEquals(0L, editor.getRevision());
    }

    /// Asserts that a named child conversion fails with TYPE_MISMATCH.
    ///
    /// @param editor editor under test
    /// @param name compound child name
    /// @param targetType rejected target type
    private static void assertRejected(
            NBTEditor<CompoundTag> editor,
            String name,
            TagType<?> targetType) throws Exception {
        NBTEditException failure = assertThrows(NBTEditException.class,
                () -> editor.convertType(NBTAddress.root().appendName(name), targetType));
        assertEquals(NBTEditException.Reason.TYPE_MISMATCH, failure.reason());
    }

    /// Copies a ByteArrayTag into a primitive array for assertions.
    ///
    /// @param tag source tag
    /// @return primitive values
    private static byte[] bytes(ByteArrayTag tag) {
        byte[] result = new byte[tag.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = tag.get(index);
        }
        return result;
    }

    /// Copies an IntArrayTag into a primitive array for assertions.
    ///
    /// @param tag source tag
    /// @return primitive values
    private static int[] ints(IntArrayTag tag) {
        int[] result = new int[tag.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = tag.get(index);
        }
        return result;
    }

    /// Copies a LongArrayTag into a primitive array for assertions.
    ///
    /// @param tag source tag
    /// @return primitive values
    private static long[] longs(LongArrayTag tag) {
        long[] result = new long[tag.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = tag.get(index);
        }
        return result;
    }
}
