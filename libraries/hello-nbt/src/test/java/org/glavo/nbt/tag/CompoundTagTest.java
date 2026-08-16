/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glavo.nbt.tag;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

final class CompoundTagTest {

    private static void assertDetached(Tag tag) {
        assertNull(tag.getParent());
        assertNull(tag.getParentTag());
        assertEquals(-1, tag.getIndex());
    }

    private static void assertAttached(CompoundTag parent, Tag tag, int index) {
        assertSame(parent, tag.getParent());
        assertSame(parent, tag.getParentTag());
        assertEquals(index, tag.getIndex());
        assertSame(tag, parent.getTag(index));
        assertSame(tag, parent.get(tag.getName()));
    }

    private static void assertContentEquals(CompoundTag expected, CompoundTag actual) {
        Supplier<String> errorMessage = () -> "Expected %s to be content-equal to %s".formatted(expected, actual);

        assertTrue(expected.contentEquals(actual), errorMessage);
        assertTrue(actual.contentEquals(expected), errorMessage);
        assertEquals(expected.contentHashCode(), actual.contentHashCode(), errorMessage);
    }

    private static void assertContentNotEquals(CompoundTag expected, CompoundTag actual) {
        Supplier<String> errorMessage = () -> "Expected %s not to be content-equal to %s".formatted(expected, actual);

        assertFalse(expected.contentEquals(actual), errorMessage);
        assertFalse(actual.contentEquals(expected), errorMessage);
        assertNotEquals(expected.contentHashCode(), actual.contentHashCode(), errorMessage);
    }

    @Test
    void testConstructorAndGetType() {
        var tag = new CompoundTag();
        assertEquals("", tag.getName());
        assertSame(TagType.COMPOUND, tag.getType());
        assertTrue(tag.isEmpty());
        assertEquals(0, tag.size());
        assertNull(tag.get("missing"));
        assertDetached(tag);

        tag = new CompoundTag().setName("root");
        assertEquals("root", tag.getName());
        assertSame(TagType.COMPOUND, tag.getType());
        assertTrue(tag.isEmpty());
        assertEquals(0, tag.size());
        assertNull(tag.get("missing"));
        assertDetached(tag);
    }

    @Test
    void testAddRenameRemoveAndClear() {
        var root = new CompoundTag().setName("root");

        var number = new IntTag(1).setName("number");
        var text = new StringTag("hello").setName("text");
        root.addTag(number);
        root.addTag(text);

        assertEquals(2, root.size());
        assertAttached(root, number, 0);
        assertAttached(root, text, 1);

        number.setName("answer");
        assertNull(root.get("number"));
        assertSame(number, root.get("answer"));
        assertThrows(IllegalArgumentException.class, () -> text.setName("answer"));
        assertEquals("text", text.getName());
        assertSame(text, root.get("text"));

        root.addTag(number);
        assertSame(text, root.getTag(0));
        assertSame(number, root.getTag(1));
        assertAttached(root, text, 0);
        assertAttached(root, number, 1);

        var replacement = new LongTag(2L).setName("answer");
        root.addTag(replacement);
        assertEquals(2, root.size());
        assertDetached(number);
        assertNull(root.get("number"));
        assertSame(replacement, root.get("answer"));
        assertAttached(root, text, 0);
        assertAttached(root, replacement, 1);

        var other = new CompoundTag().setName("other");
        var moved = new ByteTag((byte) 9).setName("old");
        other.addTag(moved);
        root.addTag("moved", moved);

        assertTrue(other.isEmpty());
        assertEquals("moved", moved.getName());
        assertSame(moved, root.get("moved"));
        assertAttached(root, moved, 2);

        Tag removed = root.removeTagAt(0);
        assertSame(text, removed);
        assertDetached(removed);
        assertNull(root.get("text"));
        assertEquals(2, root.size());
        assertAttached(root, replacement, 0);
        assertAttached(root, moved, 1);

        root.removeTag(moved);
        assertDetached(moved);
        assertNull(root.get("moved"));
        assertEquals(1, root.size());
        assertAttached(root, replacement, 0);

        assertThrows(IllegalArgumentException.class, () -> root.removeTag(text));

        root.clear();
        assertTrue(root.isEmpty());
        assertEquals(0, root.size());
        assertNull(root.get("answer"));
        assertDetached(replacement);
    }

    @Test
    void testTypedGetters() {
        var tag = new CompoundTag();
        tag.addTags(
                new ByteTag((byte) 7).setName("byte"),
                new ShortTag((short) 8).setName("short"),
                new IntTag(9).setName("int"),
                new LongTag(10L).setName("long"),
                new FloatTag(1.5f).setName("float"),
                new DoubleTag(2.5).setName("double"),
                new StringTag("hello").setName("string")
        );

        assertEquals((byte) 7, tag.getByte("byte"));
        assertEquals(Byte.valueOf((byte) 7), tag.getByteOrNull("byte"));
        assertEquals((byte) 7, tag.getByteOrZero("byte"));
        assertEquals((byte) 7, tag.getByteOrElse("byte", (byte) 42));
        assertThrows(NoSuchElementException.class, () -> tag.getByte("missing"));
        assertThrows(NoSuchElementException.class, () -> tag.getByte("string"));
        assertNull(tag.getByteOrNull("missing"));
        assertEquals((byte) 0, tag.getByteOrZero("missing"));
        assertEquals((byte) 42, tag.getByteOrElse("missing", (byte) 42));

        assertEquals((short) 8, tag.getShort("short"));
        assertEquals(Short.valueOf((short) 8), tag.getShortOrNull("short"));
        assertEquals((short) 8, tag.getShortOrZero("short"));
        assertEquals((short) 8, tag.getShortOrElse("short", (short) 42));
        assertThrows(NoSuchElementException.class, () -> tag.getShort("missing"));
        assertNull(tag.getShortOrNull("missing"));
        assertEquals((short) 0, tag.getShortOrZero("missing"));
        assertEquals((short) 42, tag.getShortOrElse("missing", (short) 42));

        assertEquals(9, tag.getInt("int"));
        assertEquals(Integer.valueOf(9), tag.getIntOrNull("int"));
        assertEquals(9, tag.getIntOrZero("int"));
        assertEquals(9, tag.getIntOrElse("int", 42));
        assertThrows(NoSuchElementException.class, () -> tag.getInt("missing"));
        assertNull(tag.getIntOrNull("missing"));
        assertEquals(0, tag.getIntOrZero("missing"));
        assertEquals(42, tag.getIntOrElse("missing", 42));

        assertEquals(10L, tag.getLong("long"));
        assertEquals(Long.valueOf(10L), tag.getLongOrNull("long"));
        assertEquals(10L, tag.getLongOrZero("long"));
        assertEquals(10L, tag.getLongOrElse("long", 42L));
        assertThrows(NoSuchElementException.class, () -> tag.getLong("missing"));
        assertNull(tag.getLongOrNull("missing"));
        assertEquals(0L, tag.getLongOrZero("missing"));
        assertEquals(42L, tag.getLongOrElse("missing", 42L));

        assertEquals(1.5f, tag.getFloat("float"));
        assertEquals(Float.valueOf(1.5f), tag.getFloatOrNull("float"));
        assertThrows(NoSuchElementException.class, () -> tag.getFloat("missing"));
        assertNull(tag.getFloatOrNull("missing"));

        assertEquals(2.5, tag.getDouble("double"));
        assertEquals(Double.valueOf(2.5), tag.getDoubleOrNull("double"));
        assertThrows(NoSuchElementException.class, () -> tag.getDouble("missing"));
        assertNull(tag.getDoubleOrNull("missing"));

        assertEquals("hello", tag.getString("string"));
        assertEquals("hello", tag.getStringOrNull("string"));
        assertEquals("hello", tag.getStringOrEmpty("string"));
        assertEquals("hello", tag.getStringOrDefault("string", "fallback"));
        assertThrows(NoSuchElementException.class, () -> tag.getString("missing"));
        assertNull(tag.getStringOrNull("missing"));
        assertEquals("", tag.getStringOrEmpty("missing"));
        assertEquals("fallback", tag.getStringOrDefault("missing", "fallback"));
    }

    @Test
    void testTypedAdd() {
        var tag = new CompoundTag();

        tag.addByte("byte", (byte) 1)
                .addBoolean("bool", true)
                .addShort("short", (short) 2)
                .addInt("int", 3)
                .addLong("long", 4L)
                .addFloat("float", 5.5f)
                .addDouble("double", 6.5)
                .addString("string", "hello");

        ByteTag byteTag = assertInstanceOf(ByteTag.class, tag.get("byte"));
        assertEquals((byte) 1, byteTag.get());
        assertEquals("byte", byteTag.getName());
        assertAttached(tag, byteTag, 0);

        ByteTag boolTag = assertInstanceOf(ByteTag.class, tag.get("bool"));
        assertTrue(boolTag.getBoolean());
        assertEquals("bool", boolTag.getName());
        assertAttached(tag, boolTag, 1);

        ShortTag shortTag = assertInstanceOf(ShortTag.class, tag.get("short"));
        assertEquals((short) 2, shortTag.get());
        assertEquals("short", shortTag.getName());
        assertAttached(tag, shortTag, 2);

        IntTag intTag = assertInstanceOf(IntTag.class, tag.get("int"));
        assertEquals(3, intTag.get());
        assertEquals("int", intTag.getName());
        assertAttached(tag, intTag, 3);

        LongTag longTag = assertInstanceOf(LongTag.class, tag.get("long"));
        assertEquals(4L, longTag.get());
        assertEquals("long", longTag.getName());
        assertAttached(tag, longTag, 4);

        FloatTag floatTag = assertInstanceOf(FloatTag.class, tag.get("float"));
        assertEquals(5.5f, floatTag.get());
        assertEquals("float", floatTag.getName());
        assertAttached(tag, floatTag, 5);

        DoubleTag doubleTag = assertInstanceOf(DoubleTag.class, tag.get("double"));
        assertEquals(6.5, doubleTag.get());
        assertEquals("double", doubleTag.getName());
        assertAttached(tag, doubleTag, 6);

        StringTag stringTag = assertInstanceOf(StringTag.class, tag.get("string"));
        assertEquals("hello", stringTag.get());
        assertEquals("string", stringTag.getName());
        assertAttached(tag, stringTag, 7);

        byte[] bytes = {1, 2, 3};
        tag.addByteArray("bytes", bytes);
        bytes[0] = 9;
        ByteArrayTag bytesTag = assertInstanceOf(ByteArrayTag.class, tag.get("bytes"));
        assertArrayEquals(new byte[]{1, 2, 3}, bytesTag.getArray());
        assertAttached(tag, bytesTag, 8);

        int[] ints = {4, 5, 6};
        tag.addIntArray("ints", ints);
        ints[0] = 9;
        IntArrayTag intsTag = assertInstanceOf(IntArrayTag.class, tag.get("ints"));
        assertArrayEquals(new int[]{4, 5, 6}, intsTag.getArray());
        assertAttached(tag, intsTag, 9);

        long[] longs = {7L, 8L, 9L};
        tag.addLongArray("longs", longs);
        longs[0] = 99L;
        LongArrayTag longsTag = assertInstanceOf(LongArrayTag.class, tag.get("longs"));
        assertArrayEquals(new long[]{7L, 8L, 9L}, longsTag.getArray());
        assertAttached(tag, longsTag, 10);

        UUID uuid = UUID.fromString("12345678-1234-5678-90ab-cdef12345678");
        tag.addUUID("uuid", uuid);
        IntArrayTag uuidTag = assertInstanceOf(IntArrayTag.class, tag.get("uuid"));
        assertTrue(uuidTag.isUUID());
        assertEquals(uuid, uuidTag.getUUID());
        assertAttached(tag, uuidTag, 11);

        tag.setInt("int", 30);
        assertSame(intTag, tag.get("int"));
        assertEquals(30, intTag.get());
        assertAttached(tag, intTag, 3);

        tag.addString("conflict", "text");
        StringTag oldConflict = assertInstanceOf(StringTag.class, tag.get("conflict"));
        assertAttached(tag, oldConflict, 12);

        tag.addInt("conflict", 114);
        assertDetached(oldConflict);
        IntTag newConflict = assertInstanceOf(IntTag.class, tag.get("conflict"));
        assertEquals(114, newConflict.get());
        assertAttached(tag, newConflict, 12);
    }

    @Test
    void testTypedSetters() {
        var tag = new CompoundTag();

        tag.setByte("byte", (byte) 1);
        ByteTag byteTag = assertInstanceOf(ByteTag.class, tag.get("byte"));
        assertEquals((byte) 1, byteTag.get());
        tag.setByte("byte", (byte) 2);
        assertSame(byteTag, tag.get("byte"));
        assertEquals((byte) 2, byteTag.get());

        tag.setBoolean("bool", true);
        ByteTag boolTag = assertInstanceOf(ByteTag.class, tag.get("bool"));
        assertTrue(boolTag.getBoolean());
        tag.setBoolean("bool", false);
        assertFalse(boolTag.getBoolean());

        tag.setShort("short", (short) 3);
        assertEquals((short) 3, assertInstanceOf(ShortTag.class, tag.get("short")).get());

        tag.setInt("int", 4);
        IntTag intTag = assertInstanceOf(IntTag.class, tag.get("int"));
        tag.setInt("int", 5);
        assertSame(intTag, tag.get("int"));
        assertEquals(5, intTag.get());

        tag.setLong("long", 6L);
        assertEquals(6L, assertInstanceOf(LongTag.class, tag.get("long")).get());

        tag.setFloat("float", 7.5f);
        assertEquals(7.5f, assertInstanceOf(FloatTag.class, tag.get("float")).get());

        tag.setDouble("double", 8.5);
        assertEquals(8.5, assertInstanceOf(DoubleTag.class, tag.get("double")).get());

        tag.setString("string", "hello");
        StringTag stringTag = assertInstanceOf(StringTag.class, tag.get("string"));
        assertEquals("hello", stringTag.get());
        tag.setString("string", "world");
        assertSame(stringTag, tag.get("string"));
        assertEquals("world", stringTag.get());

        byte[] bytes = {1, 2, 3};
        tag.setByteArray("bytes", bytes);
        bytes[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, assertInstanceOf(ByteArrayTag.class, tag.get("bytes")).getArray());

        int[] ints = {4, 5, 6};
        tag.setIntArray("ints", ints);
        ints[0] = 9;
        assertArrayEquals(new int[]{4, 5, 6}, assertInstanceOf(IntArrayTag.class, tag.get("ints")).getArray());

        long[] longs = {7L, 8L, 9L};
        tag.setLongArray("longs", longs);
        longs[0] = 99L;
        assertArrayEquals(new long[]{7L, 8L, 9L}, assertInstanceOf(LongArrayTag.class, tag.get("longs")).getArray());

        UUID uuid = UUID.fromString("12345678-1234-5678-90ab-cdef12345678");
        tag.setUUID("uuid", uuid);
        IntArrayTag uuidTag = assertInstanceOf(IntArrayTag.class, tag.get("uuid"));
        assertTrue(uuidTag.isUUID());
        assertEquals(uuid, uuidTag.getUUID());

        tag.setString("conflict", "text");
        assertThrows(IllegalStateException.class, () -> tag.setInt("conflict", 114));
    }

    @Test
    void testCloneEqualsAndToString() {
        var tag = new CompoundTag().setName("root");
        tag.addTag("answer", new ByteTag((byte) 42));

        var child = new CompoundTag().setName("child");
        child.addTag("message", new StringTag("hello"));
        tag.addTag(child);

        var sameContentDifferentOrder = new CompoundTag().setName("root");
        var child2 = new CompoundTag().setName("child");
        child2.addTag("message", new StringTag("hello"));
        sameContentDifferentOrder.addTag(child2);
        sameContentDifferentOrder.addTag("answer", new ByteTag((byte) 42));

        assertEquals(tag, sameContentDifferentOrder);
        assertEquals(tag.hashCode(), sameContentDifferentOrder.hashCode());
        assertContentEquals(tag, sameContentDifferentOrder);

        var differentName = sameContentDifferentOrder.clone();
        differentName.setName("other");
        assertNotEquals(tag, differentName);
        assertContentEquals(tag, differentName);

        var differentContent = new CompoundTag().setName("root");
        differentContent.addTag("answer", new ByteTag((byte) 41));
        differentContent.addTag(child2.clone());
        assertNotEquals(tag, differentContent);
        assertContentNotEquals(tag, differentContent);

        var clone = tag.clone();
        assertNotSame(tag, clone);
        assertDetached(clone);
        assertEquals(tag, clone);
        assertContentEquals(tag, clone);

        ByteTag clonedAnswer = assertInstanceOf(ByteTag.class, clone.get("answer"));
        CompoundTag clonedChild = assertInstanceOf(CompoundTag.class, clone.get("child"));
        assertNotSame(tag.get("answer"), clonedAnswer);
        assertNotSame(child, clonedChild);
        assertAttached(clone, clonedAnswer, 0);
        assertAttached(clone, clonedChild, 1);

        clonedAnswer.set((byte) 99);
        clonedChild.setString("message", "world");
        assertEquals((byte) 42, tag.getByte("answer"));
        assertEquals("hello", child.getString("message"));
        assertEquals((byte) 99, clone.getByte("answer"));
        assertEquals("world", clonedChild.getString("message"));

        assertEquals("{}", new CompoundTag().toString());
        assertEquals("""
                root: {
                    answer: 42B,
                    child: { message: "hello" }
                }""", tag.toString());
    }
}
