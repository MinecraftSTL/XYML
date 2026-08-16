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

/// A [value tag][ValueTag] that holds a 1 byte integer or a boolean.
///
/// @see Tag
/// @see ValueTag
public final class ByteTag extends ValueTag<Byte> {
    private byte value;

    /// Creates a new ByteTag with an empty name and a value of `0`.
    public ByteTag() {
        this((byte) 0);
    }

    /// Creates a new ByteTag with the given value.
    public ByteTag(byte value) {
        this.value = value;
    }

    /// Creates a new ByteTag with the given boolean value.
    public ByteTag(boolean value) {
        this((byte) (value ? 1 : 0));
    }

    @Override
    @Contract(pure = true)
    public TagType<ByteTag> getType() {
        return TagType.BYTE;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public ByteTag setName(String name) throws IllegalArgumentException {
        setName0(name);
        return this;
    }

    /// Returns the value of the tag.
    @Contract(pure = true)
    public byte get() {
        return value;
    }

    /// Returns the value of the tag converted to an unsigned integer.
    @Contract(pure = true)
    public int getUnsigned() {
        return Byte.toUnsignedInt(value);
    }

    /// Returns the value of the tag as a boolean.
    @Contract(pure = true)
    public boolean getBoolean() {
        // Should stricter checks be performed?
        return value != 0;
    }

    @Override
    @Contract(pure = true)
    public Byte getValue() {
        return value;
    }

    @Override
    @Contract(pure = true)
    public String getAsString() {
        return Byte.toString(value);
    }

    void setDirect(byte value) {
        this.value = value;
    }

    /// Sets the value of the tag.
    @Contract(value = "_ -> this", mutates = "this")
    public ByteTag set(byte value) {
        if (getParent() instanceof ByteArrayTag parent) {
            assert parent.getTagOrNull(getIndex()) == this;

            parent.setDirect(getIndex(), value);
        }

        setDirect(value);
        return this;
    }

    /// Sets the value of the tag from an unsigned integer.
    @Contract(value = "_ -> this", mutates = "this")
    public ByteTag setUnsigned(int value) {
        set((byte) value);
        return this;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public ByteTag setValue(Byte value) {
        set(value);
        return this;
    }

    /// Sets the boolean value of the tag.
    ///
    /// If the `value` is `true`, the tag will be set to `1`; otherwise, it will be set to `0`.
    @Contract(value = "_ -> this", mutates = "this")
    public ByteTag setBoolean(boolean value) {
        set((byte) (value ? 1 : 0));
        return this;
    }

    @Override
    void readContent(DataReader reader) throws IOException {
        set(reader.readByte());
    }

    @Override
    void writeContent(DataWriter writer) throws IOException {
        writer.writeByte(value);
    }

    @Override
    @Contract(pure = true)
    public int contentHashCode() {
        return Byte.hashCode(value);
    }

    @Override
    @Contract(pure = true)
    public boolean contentEquals(Tag other) {
        return other instanceof ByteTag that && value == that.value;
    }

    @Override
    @Contract(value = "-> new", pure = true)
    public ByteTag clone() {
        return new ByteTag(value).setName(name);
    }
}
