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

/// A [value tag][ValueTag] that holds an 8 byte integer.
///
/// @see Tag
/// @see ValueTag
public final class LongTag extends ValueTag<Long> {
    private long value;

    /// Creates a new LongTag with an empty name and a value of `0`.
    public LongTag() {
        this(0L);
    }

    /// Creates a new LongTag with the given value.
    public LongTag(long value) {
        this.value = value;
    }

    @Override
    @Contract(pure = true)
    public TagType<LongTag> getType() {
        return TagType.LONG;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public LongTag setName(String name) throws IllegalArgumentException {
        setName0(name);
        return this;
    }

    /// Returns the value of the tag.
    @Contract(pure = true)
    public long get() {
        return value;
    }

    @Override
    @Contract(pure = true)
    public Long getValue() {
        return value;
    }

    @Override
    @Contract(pure = true)
    public String getAsString() {
        return Long.toString(value);
    }

    void setDirect(long value) {
        this.value = value;
    }

    /// Sets the value of the tag.
    @Contract(value = "_ -> this", mutates = "this")
    public LongTag set(long value) {
        if (getParent() instanceof LongArrayTag parent) {
            assert parent.getTagOrNull(getIndex()) == this;

            parent.setDirect(getIndex(), value);
        }

        setDirect(value);
        return this;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public LongTag setValue(Long value) {
        set(value);
        return this;
    }

    @Override
    void readContent(DataReader reader) throws IOException {
        set(reader.readLong());
    }

    @Override
    void writeContent(DataWriter writer) throws IOException {
        writer.writeLong(value);
    }

    @Override
    @Contract(pure = true)
    public int contentHashCode() {
        return Long.hashCode(value);
    }

    @Override
    @Contract(pure = true)
    public boolean contentEquals(Tag other) {
        return other instanceof LongTag that && value == that.value;
    }

    @Override
    @Contract(value = "-> new", pure = true)
    public LongTag clone() {
        return new LongTag(value).setName(name);
    }
}
