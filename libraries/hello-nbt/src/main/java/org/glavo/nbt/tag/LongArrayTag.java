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
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PrimitiveIterator;
import java.util.stream.LongStream;

/// A tag that holds an array of [long tags][LongTag].
///
/// @see Tag
/// @see ParentTag
/// @see ArrayTag
/// @see LongTag
public final class LongArrayTag extends ArrayTag<Long, LongTag, long[], LongBuffer> {

    /// Creates a new LongArrayTag with an empty name and an empty array.
    public LongArrayTag() {
    }

    /// Creates a new LongArrayTag with an empty name and the given value.
    ///
    /// The value is cloned to avoid external modifications.
    public LongArrayTag(long[] values) {
        setAll(values);
    }

    @Override
    ArrayAccessor<Long, LongTag, long[], LongBuffer> accessor() {
        return ArrayAccessor.LONG_ARRAY;
    }

    @Override
    @Contract(pure = true)
    public TagType<LongArrayTag> getType() {
        return TagType.LONG_ARRAY;
    }

    @Override
    @Contract(pure = true)
    public TagType<LongTag> getElementType() {
        return TagType.LONG;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public LongArrayTag setName(String name) throws IllegalArgumentException {
        setName0(name);
        return this;
    }

    /// Returns the element at the given index.
    ///
    /// @throws IndexOutOfBoundsException if the index is out of bounds.
    @Contract(pure = true)
    public long get(int index) throws IndexOutOfBoundsException {
        Objects.checkIndex(index, size);
        return values[index];
    }

    void setDirect(int index, long value) {
        values[index] = value;
    }

    /// Sets the value at the given index.
    ///
    /// @throws IndexOutOfBoundsException if the index is out of bounds.
    @Contract(value = "_, _ -> this", mutates = "this")
    public LongArrayTag set(int index, long value) throws IndexOutOfBoundsException {
        Objects.checkIndex(index, size);
        setDirect(index, value);

        LongTag tag = getTagOrNull(index);
        if (tag != null) {
            tag.setDirect(value);
        }
        return this;
    }

    /// {@inheritDoc}
    ///
    /// @see #set(int, long)
    @Override
    @Contract(value = "_, _ -> this", mutates = "this")
    @ApiStatus.Obsolete
    public LongArrayTag set(int index, Long value) throws IndexOutOfBoundsException {
        set(index, value.longValue());
        return this;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public LongArrayTag setAll(long... array) { // Override to use varargs
        super.setAll(array);
        return this;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this,param1")
    public LongArrayTag setAll(LongBuffer buffer) {
        super.setAll(buffer);
        return this;
    }

    /// Appends the specified value to the end of this array.
    @Contract(value = "_ -> this", mutates = "this")
    public LongArrayTag add(long value) {
        ensureValuesCapacityForAdd();
        values[size++] = value;
        return this;
    }

    /// {@inheritDoc}
    ///
    /// @see #add(long)
    @Override
    @Contract(value = "_ -> this", mutates = "this")
    @ApiStatus.Obsolete
    public LongArrayTag add(Long value) {
        add(value.longValue());
        return this;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this,param1")
    public LongArrayTag addTag(LongTag tag) throws IllegalArgumentException {
        super.addTag(tag);
        return this;
    }

    @Override
    public PrimitiveIterator.OfLong valueIterator() {
        final long[] array = this.values;
        final int size = this.size;
        return new PrimitiveIterator.OfLong() {
            private int cursor;

            @Override
            public boolean hasNext() {
                return cursor < size;
            }

            @Override
            public long nextLong() {
                if (cursor >= size) {
                    throw new NoSuchElementException();
                }
                return array[cursor++];
            }
        };
    }

    /// Returns a sequential [LongStream] with this value as its source.
    @Override
    @Contract(pure = true)
    public LongStream valueStream() {
        return Arrays.stream(values, 0, size);
    }

    @Override
    void readContent(DataReader reader) throws IOException {
        clear();
        int len = reader.readInt();
        setArrayWithoutClone(reader.readLongArray(len), len);
    }

    @Override
    void writeContent(DataWriter writer) throws IOException {
        writer.writeInt(size);
        writer.writeLongArrayDirect(values, 0, size);
    }

    @Override
    @Contract(value = "-> new", pure = true)
    public LongArrayTag clone() {
        LongArrayTag tag = new LongArrayTag();
        tag.setName0(name);
        if (size > 0) {
            tag.values = Arrays.copyOf(values, size);
        }
        return tag;
    }

}
