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
import org.glavo.nbt.internal.input.DataReader;
import org.glavo.nbt.internal.output.DataWriter;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/// A tag that holds an array of [byte tags][ByteTag].
///
/// @see Tag
/// @see ParentTag
/// @see ArrayTag
/// @see ByteTag
public final class ByteArrayTag extends ArrayTag<Byte, ByteTag, byte[], ByteBuffer> {
    /// Creates a new ByteArrayTag with an empty name and an empty array.
    public ByteArrayTag() {
    }

    /// Creates a new ByteArrayTag with an empty name and the given value.
    ///
    /// The value is cloned to avoid external modifications.
    public ByteArrayTag(byte[] values) {
        setAll(values);
    }

    @Override
    ArrayAccessor<Byte, ByteTag, byte[], ByteBuffer> accessor() {
        return ArrayAccessor.BYTE_ARRAY;
    }

    @Override
    public TagType<ByteArrayTag> getType() {
        return TagType.BYTE_ARRAY;
    }

    @Override
    @Contract(pure = true)
    public TagType<ByteTag> getElementType() {
        return TagType.BYTE;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public ByteArrayTag setName(String name) throws IllegalArgumentException {
        setName0(name);
        return this;
    }

    /// Returns the element at the given index.
    ///
    /// @throws IndexOutOfBoundsException if the index is out of bounds.
    @Contract(pure = true)
    public byte get(int index) throws IndexOutOfBoundsException {
        Objects.checkIndex(index, size);
        return values[index];
    }

    void setDirect(int index, byte value) {
        values[index] = value;
    }

    /// Sets the value at the given index.
    ///
    /// @throws IndexOutOfBoundsException if the index is out of bounds.
    @Contract(value = "_, _ -> this", mutates = "this")
    public ByteArrayTag set(int index, byte value) throws IndexOutOfBoundsException {
        Objects.checkIndex(index, size);
        setDirect(index, value);

        ByteTag tag = getTagOrNull(index);
        if (tag != null) {
            tag.setDirect(value);
        }
        return this;
    }

    /// {@inheritDoc}
    ///
    /// @see #set(int, byte)
    @Override
    @Contract(value = "_, _ -> this", mutates = "this")
    @ApiStatus.Obsolete
    public ByteArrayTag set(int index, Byte value) throws IndexOutOfBoundsException {
        set(index, value.byteValue());
        return this;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public ByteArrayTag setAll(byte... array) { // Override to use varargs
        super.setAll(array);
        return this;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this,param1")
    public ByteArrayTag setAll(ByteBuffer buffer) {
        super.setAll(buffer);
        return this;
    }

    /// Appends the specified value to the end of this array.
    @Contract(value = "_ -> this", mutates = "this")
    public ByteArrayTag add(byte value) {
        ensureValuesCapacityForAdd();
        values[size++] = value;
        return this;
    }

    /// {@inheritDoc}
    ///
    /// @see #add(byte)
    @Override
    @Contract(value = "_ -> this", mutates = "this")
    @ApiStatus.Obsolete
    public ByteArrayTag add(Byte value) {
        add(value.byteValue());
        return this;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this,param1")
    public ByteArrayTag addTag(ByteTag tag) throws IllegalArgumentException {
        super.addTag(tag);
        return this;
    }

    @Override
    public Iterator<Byte> valueIterator() {
        final byte[] array = this.values;
        final int size = this.size;
        return new Iterator<>() {
            private int cursor;

            @Override
            public boolean hasNext() {
                return cursor < size;
            }

            @Override
            public Byte next() {
                if (cursor >= size) {
                    throw new NoSuchElementException();
                }
                return array[cursor++];
            }
        };
    }

    @Override
    @Contract(pure = true)
    public Stream<Byte> valueStream() {
        return isEmpty()
                ? Stream.empty()
                : StreamSupport.stream(Spliterators.spliterator(valueIterator(), size(), Spliterator.ORDERED | Spliterator.IMMUTABLE),
                false);
    }

    @Override
    void readContent(DataReader reader) throws IOException {
        clear();
        int len = reader.readInt();
        setArrayWithoutClone(reader.readByteArray(len), len);
    }

    @Override
    void writeContent(DataWriter writer) throws IOException {
        writer.writeInt(size);
        writer.writeByteArrayDirect(values, 0, size);
    }

    @Override
    @Contract(value = "-> new", pure = true)
    public ByteArrayTag clone() {
        ByteArrayTag tag = new ByteArrayTag();
        tag.setName0(name);
        if (size > 0) {
            tag.values = Arrays.copyOf(values, size);
        }
        return tag;
    }
}
