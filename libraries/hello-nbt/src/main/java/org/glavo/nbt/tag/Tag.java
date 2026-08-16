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

import org.glavo.nbt.NBTElement;
import org.glavo.nbt.NBTParent;
import org.glavo.nbt.internal.input.DataReader;
import org.glavo.nbt.internal.output.DataWriter;
import org.glavo.nbt.internal.snbt.SNBTWriter;
import org.glavo.nbt.io.SNBTCodec;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

/// Represents a tag in NBT (Named Binary Tag) format.
///
/// A tag is a basic unit of data in NBT, which have [a type][#getType()], [a name][#getName()], and a payload.
///
/// The payload of a tag can be a primitive value, an array of primitive values, or a collection of other tags.
///
/// Each tag may have a [parent][#getParent()], which can be a [parent tag][ParentTag] or a [chunk][org.glavo.nbt.chunk.Chunk].
/// Tags form a tree structure, where each tag has a unique position in the tree:
/// It can have at most one parent and cannot belong to multiple parents simultaneously;
/// and it cannot be multiple children of the same parent at the same time.
///
/// These are the all possible types of tags:
///
/// - [ValueTag]: A tag that holds a value.
///     - [ByteTag]: A tag that holds a 1 byte integer or a boolean.
///     - [ShortTag]: A tag that holds a 2 byte integer.
///     - [IntTag]: A tag that holds a 4 byte integer.
///     - [LongTag]: A tag that holds an 8 byte integer.
///     - [FloatTag]: A tag that holds a 4 byte floating point number.
///     - [DoubleTag]: A tag that holds an 8 byte floating point number.
///     - [StringTag]: A tag that holds a Unicode string.
/// - [ParentTag]: A tag that holds a collection of other tags.
///     - [CompoundTag]: A tag that holds a collection of named tags.
///     - [ListTag]: A tag that holds a collection of unnamed tags.
///     - [ArrayTag]: A tag that holds an array of primitive values.
///         - [ByteArrayTag]: A tag that holds an array of [byte tags][ByteTag].
///         - [IntArrayTag]: A tag that holds an array of [int tags][IntTag] or a UUID.
///         - [LongArrayTag]: A tag that holds an array of [long tags][LongTag].
///
/// @author Glavo
/// @see <a href="https://minecraft.wiki/w/NBT_format">NBT format - Minecraft Wiki</a>
/// @see ValueTag
/// @see ParentTag
public sealed abstract class Tag implements NBTElement
        permits ValueTag, ParentTag {

    String name = "";

    private transient @Nullable NBTParent<? extends Tag> parent;
    private transient int index = -1;

    Tag() {
    }

    /// Returns the type of the tag.
    ///
    /// @see TagType
    @Contract(pure = true)
    public abstract TagType<?> getType();

    /// Returns the name of the tag, or an empty string if it has no name.
    @Contract(pure = true)
    public final String getName() {
        return name;
    }

    final void setName0(String name) {
        // If the name is the same as the current name, do nothing.
        if (name.equals(this.name)) { // implicit null check
            return;
        }

        if (parent instanceof ParentTag<?> parentTag) {
            parentTag.preUpdateSubTagName(this, this.name, name);
        }

        this.name = name;
    }

    /// Set the name of the tag.
    ///
    /// If this tag is a child of a compound tag, the new name must not be used by any other subtag of the compound tag.
    ///
    /// If this tag is a child of a list tag, the new name must be empty.
    ///
    /// @throws IllegalArgumentException if this tag is a child of a parent tag and the name is not valid for the parent tag.
    /// @return this tag.
    @Contract(value = "_ -> this", mutates = "this")
    public Tag setName(String name) throws IllegalArgumentException {
        setName0(name);
        return this;
    }

    /// If the tag is a child of a [parent][NBTParent], returns the index of the tag in its parent; otherwise, returns `-1`.
    @Contract(pure = true)
    public final int getIndex() {
        return index;
    }

    /// Updates the index of this tag in its parent tag.
    final void setIndex(int index) {
        this.index = index;
    }

    /// If the tag is a child of a [parent][NBTParent], returns the parent; otherwise, returns `null`.
    @Override
    @Contract(pure = true)
    public final @Nullable NBTParent<? extends Tag> getParent() {
        return parent;
    }

    /// If the tag is a child of a [parent tag][ParentTag], returns the parent tag; otherwise, returns `null`.
    ///
    /// @see ParentTag
    @Contract(pure = true)
    public final @Nullable ParentTag<?> getParentTag() {
        return parent instanceof ParentTag<?> parentTag ? parentTag : null;
    }

    /// Sets the parent of this tag.
    ///
    /// Internal method, should only be called by [ParentTag] or [org.glavo.nbt.chunk.Chunk] when adding or removing subtags.
    ///
    /// @see ParentTag
    @Contract(mutates = "this")
    final void setParent(@Nullable NBTParent<? extends Tag> parent, int index) {
        assert parent == null ^ index >= 0;
        this.parent = parent;
        this.index = index;
    }

    /// Internal method for reading the content of the tag.
    abstract void readContent(DataReader reader) throws IOException;

    /// Internal method for writing the content of the tag.
    abstract void writeContent(DataWriter writer) throws IOException;

    @Override
    public final String toString() {
        var builder = new StringBuilder();
        try {
            new SNBTWriter<>(SNBTCodec.of(), builder).writeTagWithName(this);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return builder.toString();
    }

    /// Returns a new tag with the same name and content as the current tag.
    ///
    /// This method always performs a deep copy, and the returned tag will not have a parent tag.
    @Override
    @Contract(value = "-> new", pure = true)
    public abstract Tag clone();

    /// Returns a hash code for this tag.
    ///
    /// The hash code is based on the content of the tag.
    /// The name, parent tag, and index are not considered.
    @Contract(pure = true)
    public abstract int contentHashCode();

    /// Returns `true` if The content of this tag is equal to the content of the given tag.
    ///
    /// The name, parent tag, and index are not considered.
    @Contract(pure = true)
    public abstract boolean contentEquals(Tag other);

    /// Returns a hash code for this tag.
    ///
    /// The hash code is based on the name, type, and content of the tag.
    /// The parent tag and index are not considered.
    @Override
    public final int hashCode() {
        return Objects.hash(name, getType(), contentHashCode());
    }

    /// Returns `true` if this tag is equal to the given tag.
    ///
    /// Two tags are considered equal if they have the same name, type, and content.
    /// The parent tag and index are not considered.
    public final boolean equals(Object obj) {
        return this == obj || obj instanceof Tag that
                && Objects.equals(this.name, that.name)
                && this.contentEquals(that);
    }

}
