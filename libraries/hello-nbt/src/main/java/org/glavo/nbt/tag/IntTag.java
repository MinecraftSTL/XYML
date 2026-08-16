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

import org.glavo.nbt.internal.input.DataReader;
import org.glavo.nbt.internal.output.DataWriter;
import org.jetbrains.annotations.Contract;

import java.io.IOException;

/// A [value tag][ValueTag] that holds a 4 byte integer.
///
/// @see Tag
/// @see ValueTag
public final class IntTag extends ValueTag<Integer> {
    private int value;

    /// Creates a new IntTag with an empty name and a value of `0`.
    public IntTag() {
        this(0);
    }

    /// Creates a new IntTag with the given value.
    public IntTag(int value) {
        this.value = value;
    }

    @Override
    @Contract(pure = true)
    public TagType<IntTag> getType() {
        return TagType.INT;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public IntTag setName(String name) throws IllegalArgumentException {
        setName0(name);
        return this;
    }

    /// Returns the value of the tag.
    @Contract(pure = true)
    public int get() {
        return value;
    }

    /// Returns the value of the tag converted to an unsigned long.
    @Contract(pure = true)
    public long getUnsigned() {
        return Integer.toUnsignedLong(value);
    }

    @Override
    @Contract(pure = true)
    public Integer getValue() {
        return value;
    }

    @Override
    @Contract(pure = true)
    public String getAsString() {
        return Integer.toString(value);
    }

    void setDirect(int value) {
        this.value = value;
    }

    /// Sets the value of the tag.
    @Contract(value = "_ -> this", mutates = "this")
    public IntTag set(int value) {
        if (getParent() instanceof IntArrayTag parent) {
            assert parent.getTagOrNull(getIndex()) == this;

            parent.setDirect(getIndex(), value);
        }

        setDirect(value);
        return this;
    }

    /// Sets the value of the tag from an unsigned long.
    @Contract(value = "_ -> this", mutates = "this")
    public IntTag setUnsigned(long value) {
        set((int) value);
        return this;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public IntTag setValue(Integer value) {
        set(value);
        return this;
    }

    @Override
    void readContent(DataReader reader) throws IOException {
        set(reader.readInt());
    }

    @Override
    void writeContent(DataWriter writer) throws IOException {
        writer.writeInt(value);
    }

    @Override
    @Contract(pure = true)
    public int contentHashCode() {
        return Integer.hashCode(value);
    }

    @Override
    @Contract(pure = true)
    public boolean contentEquals(Tag other) {
        return other instanceof IntTag that && value == that.value;
    }

    @Override
    @Contract(value = "-> new", pure = true)
    public IntTag clone() {
        return new IntTag(value).setName(name);
    }
}
