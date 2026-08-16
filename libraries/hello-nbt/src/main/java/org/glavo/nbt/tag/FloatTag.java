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

/// A [value tag][ValueTag] that holds a 4 byte floating point number.
///
/// @see Tag
/// @see ValueTag
public final class FloatTag extends ValueTag<Float> {
    private float value;

    /// Creates a new FloatTag with an empty name and a value of `0.0`.
    public FloatTag() {
        this(0.0f);
    }

    /// Creates a new FloatTag with the given value.
    public FloatTag(float value) {
        this.value = value;
    }

    @Override
    @Contract(pure = true)
    public TagType<FloatTag> getType() {
        return TagType.FLOAT;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public FloatTag setName(String name) throws IllegalArgumentException {
        setName0(name);
        return this;
    }

    /// Returns the value of the tag.
    @Contract(pure = true)
    public float get() {
        return value;
    }

    @Override
    @Contract(pure = true)
    public Float getValue() {
        return value;
    }

    @Override
    @Contract(pure = true)
    public String getAsString() {
        return Float.toString(value);
    }

    /// Sets the value of the tag.
    @Contract(value = "_ -> this", mutates = "this")
    public FloatTag set(float value) {
        this.value = value;
        return this;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public FloatTag setValue(Float value) {
        this.value = value;
        return this;
    }

    @Override
    void readContent(DataReader reader) throws IOException {
        set(reader.readFloat());
    }

    @Override
    void writeContent(DataWriter writer) throws IOException {
        writer.writeFloat(value);
    }

    @Override
    @Contract(pure = true)
    public int contentHashCode() {
        return Float.hashCode(value);
    }

    @Override
    @Contract(pure = true)
    public boolean contentEquals(Tag other) {
        return other instanceof FloatTag that && Float.floatToIntBits(value) == Float.floatToIntBits(that.value);
    }

    @Override
    @Contract(value = "-> new", pure = true)
    public FloatTag clone() {
        return new FloatTag(value).setName(name);
    }
}
