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
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// This class represents the type of [tag][Tag] in NBT (Named Binary Tag) format.
///
/// @see Tag
public abstract class TagType<T extends Tag> {
    /// A [value tag][ValueTag] that holds a 1 byte integer or a boolean.
    ///
    /// @see ByteTag
    public static final TagType<ByteTag> BYTE = new TagType<>("TAG_Byte", 0x01, ByteTag.class) {
        @Override
        public ByteTag createTag() {
            return new ByteTag();
        }
    };

    /// A [value tag][ValueTag] that holds a 2 byte integer.
    ///
    /// @see ShortTag
    public static final TagType<ShortTag> SHORT = new TagType<>("TAG_Short", 0x02, ShortTag.class) {
        @Override
        public ShortTag createTag() {
            return new ShortTag();
        }
    };

    /// A [value tag][ValueTag] that holds a 4 byte integer.
    ///
    /// @see IntTag
    public static final TagType<IntTag> INT = new TagType<>("TAG_Int", 0x03, IntTag.class) {
        @Override
        public IntTag createTag() {
            return new IntTag();
        }
    };

    /// A [value tag][ValueTag] that holds an 8 byte integer.
    ///
    /// @see LongTag
    public static final TagType<LongTag> LONG = new TagType<>("TAG_Long", 0x04, LongTag.class) {
        @Override
        public LongTag createTag() {
            return new LongTag();
        }
    };

    /// A [value tag][ValueTag] that holds a 4 byte floating point number.
    ///
    /// @see FloatTag
    public static final TagType<FloatTag> FLOAT = new TagType<>("TAG_Float", 0x05, FloatTag.class) {
        @Override
        public FloatTag createTag() {
            return new FloatTag();
        }
    };

    /// A [value tag][ValueTag] that holds an 8 byte floating point number.
    ///
    /// @see DoubleTag
    public static final TagType<DoubleTag> DOUBLE = new TagType<>("TAG_Double", 0x06, DoubleTag.class) {
        @Override
        public DoubleTag createTag() {
            return new DoubleTag();
        }
    };

    /// A tag that holds an array of [byte tags][ByteTag].
    ///
    /// @see ByteArrayTag
    public static final TagType<ByteArrayTag> BYTE_ARRAY = new TagType<>("TAG_Byte_Array", 0x07, ByteArrayTag.class) {
        @Override
        public ByteArrayTag createTag() {
            return new ByteArrayTag();
        }
    };

    /// A [value tag][ValueTag] that holds a Unicode string.
    ///
    /// @see StringTag
    public static final TagType<StringTag> STRING = new TagType<>("TAG_String", 0x08, StringTag.class) {
        @Override
        public StringTag createTag() {
            return new StringTag();
        }
    };

    /// A [value tag][ValueTag] that holds a collection of other tags.
    ///
    /// @see ListTag
    public static final TagType<ListTag<?>> LIST = new TagType<>("TAG_List", 0x09, ListTag.class) {
        @Override
        public ListTag<?> createTag() {
            return new ListTag<>();
        }
    };

    /// A [value tag][ValueTag] that holds a collection of named tags.
    ///
    /// @see CompoundTag
    public static final TagType<CompoundTag> COMPOUND = new TagType<>("TAG_Compound", 0x0A, CompoundTag.class) {
        @Override
        public CompoundTag createTag() {
            return new CompoundTag();
        }
    };

    /// A tag that holds an array of [int tags][IntTag].
    ///
    /// @see IntArrayTag
    public static final TagType<IntArrayTag> INT_ARRAY = new TagType<>("TAG_Int_Array", 0x0B, IntArrayTag.class) {
        @Override
        public IntArrayTag createTag() {
            return new IntArrayTag();
        }
    };

    /// A tag that holds an array of [long tags][LongTag].
    ///
    /// @see LongArrayTag
    public static final TagType<LongArrayTag> LONG_ARRAY = new TagType<>("TAG_Long_Array", 0x0C, LongArrayTag.class) {
        @Override
        public LongArrayTag createTag() {
            return new LongArrayTag();
        }
    };

    /// Returns the tag type by its id; returns `null` if the id is invalid.
    public static @Nullable TagType<?> getById(byte id) {
        return id > 0 && id < ID_TO_TYPE.length ? ID_TO_TYPE[id] : null;
    }

    private static final List<TagType<?>> VALUES;
    private static final @Nullable TagType<?>[] ID_TO_TYPE;

    static {
        VALUES = List.of(BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY);

        ID_TO_TYPE = new TagType<?>[VALUES.size() + 1];

        for (int i = 0; i < VALUES.size(); i++) {
            TagType<?> type = VALUES.get(i);

            assert i == type.id() - 1 : "Invalid order: " + type.name();
            assert ID_TO_TYPE[Byte.toUnsignedInt(type.id())] == null : "Duplicate ID: " + type.id();

            ID_TO_TYPE[Byte.toUnsignedInt(type.id())] = type;
        }
    }

    private final String name;
    private final byte id;
    private final Class<T> tagClass;

    @SuppressWarnings("unchecked")
    private TagType(String name, int id, Class<?> tagClass) {
        this.name = name;
        this.id = (byte) id;
        this.tagClass = (Class<T>) tagClass;
    }

    /// Returns the name of the tag type.
    @Contract(pure = true)
    public String name() {
        return name;
    }

    /// Returns the full name of the tag type.
    @Contract(pure = true)
    public String getFullName() {
        return name;
    }

    /// Returns the ID of the tag type.
    @Contract(pure = true)
    public byte id() {
        return id;
    }

    /// Returns the class of the tag type.
    @Contract(pure = true)
    public Class<T> tagClass() {
        return tagClass;
    }

    /// Creates a new tag of this type.
    @Contract("-> new")
    public abstract T createTag();

    /// Creates a new tag of this type with the given name.
    @Contract("_ -> new")
    public T createTag(String name) {
        T tag = createTag();
        tag.setName(name);
        return tag;
    }

    @Override
    public String toString() {
        return name;
    }
}
