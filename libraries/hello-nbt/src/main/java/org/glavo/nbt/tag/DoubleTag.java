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

/// A [value tag][ValueTag] that holds an 8 byte floating point number.
///
/// @see Tag
/// @see ValueTag
public final class DoubleTag extends ValueTag<Double> {
    private double value;

    /// Creates a new DoubleTag with an empty name and a value of `0.0`.
    public DoubleTag() {
        this(0.0);
    }

    /// Creates a new DoubleTag with the given value.
    public DoubleTag(double value) {
        this.value = value;
    }

    @Override
    @Contract(pure = true)
    public TagType<DoubleTag> getType() {
        return TagType.DOUBLE;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public DoubleTag setName(String name) throws IllegalArgumentException {
        setName0(name);
        return this;
    }

    /// Returns the value of the tag.
    @Contract(pure = true)
    public double get() {
        return value;
    }

    @Override
    @Contract(pure = true)
    public Double getValue() {
        return value;
    }

    @Override
    @Contract(pure = true)
    public String getAsString() {
        return Double.toString(value);
    }

    /// Sets the value of the tag.
    @Contract(value = "_ -> this", mutates = "this")
    public DoubleTag set(double value) {
        this.value = value;
        return this;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public DoubleTag setValue(Double value) {
        this.value = value;
        return this;
    }

    @Override
    void readContent(DataReader reader) throws IOException {
        set(reader.readDouble());
    }

    @Override
    void writeContent(DataWriter writer) throws IOException {
        writer.writeDouble(value);
    }

    @Override
    @Contract(pure = true)
    public int contentHashCode() {
        return Double.hashCode(value);
    }

    @Override
    @Contract(pure = true)
    public boolean contentEquals(Tag other) {
        return other instanceof DoubleTag that && Double.doubleToLongBits(value) == Double.doubleToLongBits(that.value);
    }

    @Override
    @Contract(value = "-> new", pure = true)
    public DoubleTag clone() {
        return new DoubleTag(value).setName(name);
    }
}
