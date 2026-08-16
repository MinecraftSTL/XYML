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

import org.glavo.nbt.internal.ArrayAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;
import java.util.stream.BaseStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

abstract class ArrayTagTest<AT extends ArrayTag<E, T, A, B>, E extends Number, T extends ValueTag<E>, A, B extends Buffer> {
    abstract ArrayAccessor<E, T, A, B> accessor();

    abstract E valueOf(long value);

    abstract AT create();

    abstract AT create(A array);

    abstract T createSubTag();

    final A newArray(int size) {
        return accessor().newArray(size);
    }

    final A emptyArray() {
        return accessor().empty();
    }

    abstract A arrayOf(long... values);

    final int getLength(A array) {
        return accessor().getLength(array);
    }

    final E get(A array, int index) {
        return accessor().get(array, index);
    }

    final void set(A array, int index, E value) {
        accessor().set(array, index, value);
    }

    abstract void set(A array, int index, long value);

    final A copyOf(A array, int newSize) {
        return accessor().copyOf(array, newSize);
    }

    abstract A randomArray(Random random, int size);

    void assertArrayEquals(A expected, A actual) {
        if (getLength(expected) != getLength(actual)) {
            throw new AssertionError("Array lengths differ: " + getLength(expected) + " != " + getLength(actual));
        }

        for (int i = 0, end = getLength(expected); i < end; i++) {
            if (!accessor().get(expected, i).equals(accessor().get(actual, i))) {
                throw new AssertionError("Array contents differ at index " + i + ": " + accessor().get(expected, i) + " != " + accessor().get(actual, i));
            }
        }
    }

    void assertValueEquals(A expected, AT tag) {
        int length = getLength(expected);

        // Test size
        assertEquals(length, tag.size(), () -> "Array lengths differ: " + length + " != " + tag.size());

        // Test getArray
        assertArrayEquals(expected, tag.getArray());

        // Test getBuffer
        assertArrayEquals(expected, accessor().get(tag.getBuffer()));

        // Test valueIterator
        Iterator<E> valueIterator = tag.valueIterator();
        for (int i = 0; i < length; i++) {
            try {
                assertEquals(get(expected, i), valueIterator.next());
            } catch (NoSuchElementException e) {
                throw new AssertionError("Array contents differ at index " + i + ": expected " + get(expected, i) + ", but iterator ran out of elements", e);
            }
        }
        assertFalse(valueIterator.hasNext());
        assertThrows(NoSuchElementException.class, valueIterator::next);

        // Test valueStream
        //noinspection resource
        BaseStream<E, ?> valueStream = tag.valueStream();
        if (valueStream instanceof IntStream intStream) {
            assertArrayEquals(expected, (A) intStream.toArray());
        } else if (valueStream instanceof LongStream longStream) {
            assertArrayEquals(expected, (A) longStream.toArray());
        } else {
            @SuppressWarnings("unchecked")
            List<Byte> list = ((Stream<Byte>) valueStream).toList();
            assertEquals(length, list.size());
            for (int i = 0; i < length; i++) {
                assertEquals(get(expected, i), list.get(i));
            }
        }

    }

    @Test
    void testConstructor() {
        AT tag = create();
        assertEquals("", tag.getName());
        assertValueEquals(emptyArray(), tag);

        tag = create(accessor().empty());
        assertEquals("", tag.getName());
        assertValueEquals(emptyArray(), tag);

        Random random = new Random(0);
        A array = randomArray(random, 10);
        tag = create(array);
        assertEquals("", tag.getName());
        assertEquals(10, tag.size());
        assertNotSame(array, tag.values);
        assertValueEquals(array, tag);
    }

    @Test
    void testAdd() {
        var tag = create();
        assertEquals(0, tag.tags.length);
        assertValueEquals(emptyArray(), tag);

        tag.add(valueOf(1));
        assertEquals(0, tag.tags.length);
        assertEquals(ArrayAccessor.DEFAULT_CAPACITY, getLength(tag.values));
        assertValueEquals(arrayOf(1L), tag);

        tag.add(valueOf(2L));
        assertEquals(0, tag.tags.length);
        assertEquals(ArrayAccessor.DEFAULT_CAPACITY, getLength(tag.values));
        assertValueEquals(arrayOf(1L, 2L), tag);

        T subTag = tag.getTag(1);
        assertEquals("", subTag.getName());
        assertEquals(2L, subTag.getValue().longValue());
        assertEquals(1, subTag.getIndex());

        subTag.setValue(valueOf(3L));
        assertSame(subTag, tag.getTag(1));
        assertEquals(2, tag.size());
        assertValueEquals(arrayOf(1L, 3L), tag);
        assertEquals(valueOf(3L), subTag.getValue());

        tag.clear();

        var random = new Random(0);
        A data = randomArray(random, 100);
        for (int i = 0; i < 100; i++) {
            tag.add(get(data, i));
        }
        assertValueEquals(data, tag);
    }

    @Test
    void testAddTag() {
        var tag = create();

        T subTag0 = createSubTag();
        subTag0.setValue(valueOf(114));
        tag.addTag(subTag0);
        assertEquals(1, tag.size());
        assertValueEquals(arrayOf(114L), tag);
        assertSame(tag, subTag0.getParentTag());
        assertEquals(0, subTag0.getIndex());
        assertSame(subTag0, tag.getTag(0));

        T subTag1 = createSubTag();
        subTag1.setValue(valueOf(123));
        tag.addTag(subTag1);
        assertEquals(2, tag.size());
        assertValueEquals(arrayOf(114L, 123L), tag);
        assertSame(tag, subTag1.getParentTag());
        assertEquals(1, subTag1.getIndex());
        assertSame(subTag1, tag.getTag(1));

        subTag0.setValue(valueOf(10));
        assertValueEquals(arrayOf(10L, 123L), tag);

        tag.set(1, valueOf(20));
        assertEquals(valueOf(20), subTag1.getValue());
        assertValueEquals(arrayOf(10L, 20L), tag);

        tag.addTag(subTag1);
        assertValueEquals(arrayOf(10L, 20L), tag);
        assertSame(subTag0, tag.getTag(0));
        assertSame(subTag1, tag.getTag(1));

        tag.addTag(subTag0);
        assertValueEquals(arrayOf(20L, 10L), tag);
        assertSame(subTag1, tag.getTag(0));
        assertSame(subTag0, tag.getTag(1));
        assertSame(tag, subTag0.getParent());
        assertSame(tag, subTag0.getParentTag());
        assertSame(tag, subTag1.getParent());
        assertSame(tag, subTag1.getParentTag());
        assertEquals(0, subTag1.getIndex());
        assertEquals(1, subTag0.getIndex());
    }

    @Test
    void testClear() {
        var tag = create();
        tag.add(valueOf(1));

        tag.clear();
        assertEquals(0, tag.size());
        assertValueEquals(emptyArray(), tag);
        assertEquals(0, getLength(tag.values));
        assertEquals(0, tag.tags.length);

        A data = randomArray(new Random(0), 100);
        for (int i = 0; i < 100; i++) {
            tag.add(get(data, i));
        }
        tag.clear();
        assertEquals(0, tag.size());
        assertValueEquals(emptyArray(), tag);
        assertEquals(0, getLength(tag.values));
        assertEquals(0, tag.tags.length);
    }

    @Test
    void testValuesView() {
        var tag = create(arrayOf(1L, 2L, 3L));
        List<E> values = tag.values();

        T subTag0 = tag.getTag(0);
        T subTag1 = tag.getTag(1);
        T subTag2 = tag.getTag(2);

        assertEquals(3, values.size());
        assertEquals(valueOf(1L), values.get(0));
        assertEquals(valueOf(2L), values.set(1, valueOf(20L)));
        assertEquals(valueOf(20L), subTag1.getValue());
        assertValueEquals(arrayOf(1L, 20L, 3L), tag);

        assertTrue(values.add(valueOf(4L)));
        assertValueEquals(arrayOf(1L, 20L, 3L, 4L), tag);

        assertEquals(valueOf(1L), values.remove(0));
        assertValueEquals(arrayOf(20L, 3L, 4L), tag);
        assertNull(subTag0.getParent());
        assertEquals(-1, subTag0.getIndex());
        assertSame(subTag1, tag.getTag(0));
        assertSame(subTag2, tag.getTag(1));
        assertEquals(0, subTag1.getIndex());
        assertEquals(1, subTag2.getIndex());

        values.clear();
        assertTrue(tag.isEmpty());
        assertValueEquals(emptyArray(), tag);
        assertNull(subTag1.getParent());
        assertNull(subTag2.getParent());
        assertEquals(-1, subTag1.getIndex());
        assertEquals(-1, subTag2.getIndex());
    }

    @Test
    void testSetAllArray() {
        var tag = create(arrayOf(1L, 2L, 3L));
        T subTag0 = tag.getTag(0);
        T subTag2 = tag.getTag(2);

        A source = arrayOf(7L, 8L, 9L, 10L);
        tag.setAll(source);

        assertEquals(0, tag.tags.length);
        assertNotSame(source, tag.values);
        assertValueEquals(arrayOf(7L, 8L, 9L, 10L), tag);
        assertNull(subTag0.getParent());
        assertNull(subTag2.getParent());
        assertEquals(-1, subTag0.getIndex());
        assertEquals(-1, subTag2.getIndex());

        set(source, 0, 114L);
        assertValueEquals(arrayOf(7L, 8L, 9L, 10L), tag);
    }

    @Test
    void testSetAllBuffer() {
        var tag = create(arrayOf(1L, 2L, 3L));
        T subTag = tag.getTag(1);

        B buffer = accessor().getReadOnlyView(arrayOf(9L, 8L, 7L, 6L, 5L), 1, 3);
        int limit = buffer.limit();

        tag.setAll(buffer);

        assertEquals(limit, buffer.position());
        assertEquals(0, tag.tags.length);
        assertValueEquals(arrayOf(8L, 7L, 6L), tag);
        assertNull(subTag.getParent());
        assertEquals(-1, subTag.getIndex());
    }

    @Test
    void testRemoveTagAtWithoutCachedSubTag() {
        var tag = create(arrayOf(10L, 20L, 30L, 40L));
        T lastTag = tag.getTag(3);

        T removed = tag.removeTagAt(1);

        assertEquals("", removed.getName());
        assertEquals(valueOf(20L), removed.getValue());
        assertNull(removed.getParent());
        assertEquals(-1, removed.getIndex());
        assertValueEquals(arrayOf(10L, 30L, 40L), tag);
        assertSame(lastTag, tag.getTag(2));
        assertEquals(2, lastTag.getIndex());
        assertNotSame(removed, tag.getTag(1));
        assertEquals(valueOf(30L), tag.getTag(1).getValue());
    }

    @Test
    void testAddTagMovesFromOtherParent() {
        var source = create();
        var target = create();

        T moved = createSubTag();
        moved.setValue(valueOf(11L));
        T remaining = createSubTag();
        remaining.setValue(valueOf(22L));

        source.addTag(moved);
        source.addTag(remaining);

        target.addTag(moved);

        assertValueEquals(arrayOf(22L), source);
        assertValueEquals(arrayOf(11L), target);
        assertSame(target, moved.getParentTag());
        assertEquals(0, moved.getIndex());
        assertSame(source, remaining.getParentTag());
        assertEquals(0, remaining.getIndex());
        assertSame(moved, target.getTag(0));
        assertSame(remaining, source.getTag(0));
    }

    @Test
    void testArrayAndBufferSnapshots() {
        var tag = create(arrayOf(5L, 6L, 7L));

        A array = tag.getArray();
        set(array, 1, 100L);
        assertValueEquals(arrayOf(5L, 6L, 7L), tag);

        B buffer1 = tag.getBuffer();
        B buffer2 = tag.getBuffer();
        assertNotSame(buffer1, buffer2);
        assertEquals(0, buffer1.position());
        assertEquals(0, buffer2.position());
        assertEquals(tag.size(), buffer1.limit());
        assertEquals(tag.size(), buffer2.limit());
        assertArrayEquals(arrayOf(5L, 6L, 7L), accessor().get(buffer1));
        assertEquals(buffer1.limit(), buffer1.position());
        assertEquals(0, buffer2.position());
    }

    @Test
    void testInvalidIndexesAndRemoveTagValidation() {
        var tag = create(arrayOf(1L, 2L, 3L));

        assertThrows(IndexOutOfBoundsException.class, () -> tag.getTag(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> tag.getValue(3));
        assertThrows(IndexOutOfBoundsException.class, () -> tag.getAsString(3));
        assertThrows(IndexOutOfBoundsException.class, () -> tag.set(3, valueOf(0L)));
        assertThrows(IndexOutOfBoundsException.class, () -> tag.removeAt(3));
        assertThrows(IndexOutOfBoundsException.class, () -> tag.removeTagAt(3));

        T foreignTag = createSubTag();
        foreignTag.setValue(valueOf(1L));
        assertThrows(IllegalArgumentException.class, () -> tag.removeTag(foreignTag));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L})
    void testSubTag(long seed) {
        Random random = new Random(seed);

        final int size = random.nextInt(128);

        A data = newArray(size);
        for (int i = 0; i < size; i++) {
            set(data, i, i);
        }

        var tag = create(data);

        // No child tags should be allocated when no child tags are used.
        assertEquals(0, tag.tags.length);

        List<T> subTags = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            T subTag = tag.getTag(i);
            assertSame(tag, subTag.getParent());
            assertSame(tag, subTag.getParentTag());
            assertEquals(i, subTag.getIndex());
            assertEquals(get(data, i), subTag.getValue());
            assertTrue(tag.tags.length >= i + 1);

            subTags.add(subTag);
        }

        // Assigned subTags should be cached.
        for (int i = 0; i < size; i++) {
            assertSame(subTags.get(i), tag.getTag(i));
        }

        // Modifying array values should also synchronize the corresponding subtag values
        for (int i = 0; i < size; i++) {
            tag.set(i, valueOf(-i));
        }
        for (int i = 0; i < size; i++) {
            assertSame(subTags.get(i), tag.getTag(i));
            assertEquals(valueOf(-i), tag.getValue(i));
        }

        // Modifying subtag values should also synchronize the array values
        for (int i = 0; i < size; i++) {
            subTags.get(i).setValue(get(data, i));
        }
        assertValueEquals(data, tag);

        while (!subTags.isEmpty()) {
            int removedIndex = random.nextInt(subTags.size());

            if (random.nextBoolean()) {
                T removedTag = tag.removeTagAt(removedIndex);
                assertSame(subTags.get(removedIndex), removedTag);
                assertEquals(-1, removedTag.getIndex());
                assertNull(removedTag.getParent());
            } else {
                tag.removeAt(removedIndex);
            }

            subTags.remove(removedIndex);

            for (int i = 0; i < subTags.size(); i++) {
                T subTag = subTags.get(i);
                assertSame(subTag, tag.getTag(i));
                assertSame(tag, subTag.getParent());
                assertEquals(i, subTag.getIndex());
            }
        }
    }

    static final class ByteArrayTagTest extends ArrayTagTest<ByteArrayTag, Byte, ByteTag, byte[], ByteBuffer> {

        @Override
        ArrayAccessor<Byte, ByteTag, byte[], ByteBuffer> accessor() {
            return ArrayAccessor.BYTE_ARRAY;
        }

        @Override
        Byte valueOf(long value) {
            return (byte) value;
        }

        @Override
        ByteArrayTag create() {
            return new ByteArrayTag();
        }

        @Override
        ByteArrayTag create(byte[] array) {
            return new ByteArrayTag(array);
        }

        @Override
        ByteTag createSubTag() {
            return new ByteTag();
        }

        @Override
        byte[] arrayOf(long... values) {
            byte[] array = new byte[values.length];
            for (int i = 0; i < values.length; i++) {
                array[i] = (byte) values[i];
            }
            return array;
        }

        @Override
        void set(byte[] array, int index, long value) {
            array[index] = (byte) value;
        }

        @Override
        byte[] randomArray(Random random, int size) {
            byte[] array = new byte[size];
            random.nextBytes(array);
            return array;
        }
    }

    static final class IntArrayTagTest extends ArrayTagTest<IntArrayTag, Integer, IntTag, int[], IntBuffer> {

        @Override
        ArrayAccessor<Integer, IntTag, int[], IntBuffer> accessor() {
            return ArrayAccessor.INT_ARRAY;
        }

        @Override
        Integer valueOf(long value) {
            return (int) value;
        }

        @Override
        IntArrayTag create() {
            return new IntArrayTag();
        }

        @Override
        IntArrayTag create(int[] array) {
            return new IntArrayTag(array);
        }

        @Override
        IntTag createSubTag() {
            return new IntTag();
        }

        @Override
        void set(int[] array, int index, long value) {
            array[index] = (int) value;
        }

        @Override
        int[] arrayOf(long... values) {
            int[] array = new int[values.length];
            for (int i = 0; i < values.length; i++) {
                array[i] = (int) values[i];
            }
            return array;
        }

        @Override
        int[] randomArray(Random random, int size) {
            return random.ints(size).limit(size).toArray();
        }

        @Test
        void testUUID() {
            var tag = new IntArrayTag();
            var uuid = UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6");

            assertFalse(tag.isUUID());
            assertThrows(IllegalStateException.class, tag::getUUID);

            tag.setUUID(uuid);
            assertTrue(tag.isUUID());
            assertEquals(4, tag.size());
            assertValueEquals(new int[]{-132296786, 2112623056, -1486552928, -920753162}, tag);
            assertEquals(uuid, tag.getUUID());

            tag.add(114);
            assertFalse(tag.isUUID());
            assertThrows(IllegalStateException.class, tag::getUUID);

            uuid = UUID.fromString("00000000-0000-0000-0000-000000000000");
            tag.setUUID(uuid);
            assertTrue(tag.isUUID());
            assertEquals(4, tag.size());
            assertValueEquals(new int[]{0, 0, 0, 0}, tag);
            assertEquals(uuid, tag.getUUID());

            tag.clear();
            assertFalse(tag.isUUID());
            assertThrows(IllegalStateException.class, tag::getUUID);

            var random = new Random(0);

            for (int i = 0; i < 100; i++) {
                uuid = new UUID(random.nextLong(), random.nextLong());
                tag.setUUID(uuid);
                assertTrue(tag.isUUID());
                assertEquals(4, tag.size());
                assertEquals(uuid, tag.getUUID());

                tag.clear();
                assertFalse(tag.isUUID());
                assertThrows(IllegalStateException.class, tag::getUUID);
            }
        }
    }

    static final class LongArrayTagTest extends ArrayTagTest<LongArrayTag, Long, LongTag, long[], LongBuffer> {
        @Override
        ArrayAccessor<Long, LongTag, long[], LongBuffer> accessor() {
            return ArrayAccessor.LONG_ARRAY;
        }

        @Override
        Long valueOf(long value) {
            return value;
        }

        @Override
        LongArrayTag create() {
            return new LongArrayTag();
        }

        @Override
        LongArrayTag create(long[] array) {
            return new LongArrayTag(array);
        }

        @Override
        LongTag createSubTag() {
            return new LongTag();
        }

        @Override
        long[] arrayOf(long... values) {
            return values;
        }

        @Override
        void set(long[] array, int index, long value) {
            array[index] = value;
        }

        @Override
        long[] randomArray(Random random, int size) {
            return random.longs(size).limit(size).toArray();
        }
    }
}
