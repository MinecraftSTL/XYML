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
import java.util.Objects;

/// A [value tag][ValueTag] that holds a Unicode string.
///
/// @see Tag
/// @see ValueTag
public final class StringTag extends ValueTag<String> {
    private String value;

    /// Creates a new StringTag with an empty name and a value of `""`.
    public StringTag() {
        this.value = "";
    }

    /// Creates a new StringTag with the given name and a value of `""`.
    public StringTag(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    @Override
    @Contract(pure = true)
    public TagType<StringTag> getType() {
        return TagType.STRING;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public StringTag setName(String name) throws IllegalArgumentException {
        setName0(name);
        return this;
    }

    /// Returns the value of the tag.
    @Contract(pure = true)
    public String get() {
        return value;
    }

    @Override
    @Contract(pure = true)
    public String getValue() {
        return value;
    }

    @Override
    @Contract(pure = true)
    public String getAsString() {
        return value;
    }

    /// Sets the value of the tag.
    @Contract(value = "_ -> this", mutates = "this")
    public StringTag set(String value) {
        this.value = Objects.requireNonNull(value);
        return this;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public StringTag setValue(String value) {
        this.value = Objects.requireNonNull(value);
        return this;
    }

    @Override
    void readContent(DataReader reader) throws IOException {
        set(reader.readString());
    }

    @Override
    void writeContent(DataWriter writer) throws IOException {
        writer.writeString(value);
    }

    @Override
    @Contract(pure = true)
    public int contentHashCode() {
        return value.hashCode();
    }

    @Override
    @Contract(pure = true)
    public boolean contentEquals(Tag other) {
        return other instanceof StringTag that && value.equals(that.value);
    }

    @Override
    @Contract(value = "-> new", pure = true)
    public StringTag clone() {
        return new StringTag(value).setName(name);
    }
}