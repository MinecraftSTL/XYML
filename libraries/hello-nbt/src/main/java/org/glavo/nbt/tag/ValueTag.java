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

import org.jetbrains.annotations.Contract;

/// Base class for tags that hold a single value.
///
/// These are the all possible types of value tags:
///
/// - [ByteTag]: A tag that holds a 1 byte integer or a boolean.
/// - [ShortTag]: A tag that holds a 2 byte integer.
/// - [IntTag]: A tag that holds a 4 byte integer.
/// - [LongTag]: A tag that holds an 8 byte integer.
/// - [FloatTag]: A tag that holds a 4 byte floating point number.
/// - [DoubleTag]: A tag that holds an 8 byte floating point number.
/// - [StringTag]: A tag that holds a Unicode string.
///
/// @see Tag
/// @see ByteTag
/// @see ShortTag
/// @see IntTag
/// @see LongTag
/// @see FloatTag
/// @see DoubleTag
/// @see StringTag
public sealed abstract class ValueTag<V> extends Tag
        permits ByteTag, ShortTag, IntTag, LongTag, FloatTag, DoubleTag, StringTag {
    ValueTag() {
    }

    @Override
    @Contract(pure = true)
    public abstract TagType<? extends ValueTag<V>> getType();

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public ValueTag<V> setName(String name) throws IllegalArgumentException {
        setName0(name);
        return this;
    }

    /// Returns the value of the tag.
    @Contract(pure = true)
    public abstract V getValue();

    /// Returns the value of the tag as a string.
    @Contract(pure = true)
    public abstract String getAsString();

    /// Sets the value of the tag.
    @Contract(value = "_ -> this", mutates = "this")
    public abstract ValueTag<V> setValue(V value);

    @Override
    @Contract(value = "-> new", pure = true)
    public abstract ValueTag<V> clone();
}
