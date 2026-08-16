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

import org.glavo.nbt.NBTParent;
import org.glavo.nbt.internal.input.DataReader;
import org.glavo.nbt.internal.input.NBTInput;
import org.glavo.nbt.internal.output.DataWriter;
import org.glavo.nbt.internal.output.NBTOutput;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;

/// A [parent tag][ParentTag] that holds an unordered collection of named tags.
///
/// All elements in the compound tag must have a unique name.
///
/// `CompoundTag` supports fluent addition/setting operations. It has two families of APIs for adding/setting subtags:
///
/// - The `add` family of APIs, used to add new subtags.
///   If a subtag with the same name already exists, the old subtag will be removed first.
/// - The `set` family of APIs, used to set the value of a subtag.
///   If no subtag with the same name exists, a new subtag will be created.
///
/// Although both can be used to add subtags, they have different behaviors for existing subtags:
///
/// - The `add` family of APIs will not throw exceptions.
///   When a subtag with the same name exists, it will overwrite the old tag.
///   These methods are more suitable for use when constructing a `CompoundTag`.
/// - The `set` family of APIs will not remove any tags from the current `CompoundTag`.
///   This means that subtags previously obtained through the `get` method will not be invalidated by the `set` method.
///   However, if the type of the subtag does not match the value being set (for example, if there is already a StringTag named `foo`,
///   but you try to call `setInt("foo", ...)`), an `IllegalStateException` will be thrown.
///   These methods are more suitable for modifying existing tags.
///
/// @see Tag
/// @see ParentTag
public final class CompoundTag extends ParentTag<Tag> {

    private final Map<String, Tag> subTagsByName = new HashMap<>();

    /// Creates a new empty compound tag with an empty name.
    public CompoundTag() {
    }

    @Override
    void preUpdateSubTagName(Tag tag, String oldName, String newName) throws IllegalArgumentException {
        if (subTagsByName.containsKey(newName)) {
            throw new IllegalArgumentException("The name '" + newName + "' is already used by another subtag");
        }

        Tag removedTag = subTagsByName.remove(oldName);
        if (removedTag != tag) {
            throw new AssertionError("Expected " + tag + ", but got " + removedTag);
        }

        subTagsByName.put(newName, tag);
    }

    @Override
    @Contract(pure = true)
    public TagType<CompoundTag> getType() {
        return TagType.COMPOUND;
    }

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public CompoundTag setName(String name) throws IllegalArgumentException {
        setName0(name);
        return this;
    }

    /// Returns the subtag with the given name, or `null` if no such subtag exists.
    @Contract(pure = true)
    public @Nullable Tag get(String name) {
        return subTagsByName.get(name);
    }

    /// Returns the byte value of the byte tag with the given name.
    ///
    /// @throws NoSuchElementException if no byte tag with the given name exists.
    public byte getByte(String name) throws NoSuchElementException {
        if (get(name) instanceof ByteTag tag) {
            return tag.get();
        } else {
            throw new NoSuchElementException("No byte tag with name " + name);
        }
    }

    /// Returns the byte value of the byte tag with the given name, or `null` if no such tag exists.
    public @Nullable Byte getByteOrNull(String name) {
        return get(name) instanceof ByteTag tag ? tag.get() : null;
    }

    /// Returns the byte value of the byte tag with the given name, or `0` if no such tag exists.
    public byte getByteOrZero(String name) {
        return get(name) instanceof ByteTag tag ? tag.get() : 0;
    }

    /// Returns the byte value of the byte tag with the given name, or the default value if no such tag exists.
    public byte getByteOrElse(String name, byte defaultValue) {
        return get(name) instanceof ByteTag tag ? tag.get() : defaultValue;
    }

    /// Returns the short value of the short tag with the given name.
    ///
    /// @throws NoSuchElementException if no short tag with the given name exists.
    public short getShort(String name) throws NoSuchElementException {
        if (get(name) instanceof ShortTag tag) {
            return tag.get();
        } else {
            throw new NoSuchElementException("No short tag with name " + name);
        }
    }

    /// Returns the short value of the short tag with the given name, or `null` if no such tag exists.
    public @Nullable Short getShortOrNull(String name) {
        return get(name) instanceof ShortTag tag ? tag.get() : null;
    }

    /// Returns the short value of the short tag with the given name, or `0` if no such tag exists.
    public short getShortOrZero(String name) {
        return get(name) instanceof ShortTag tag ? tag.get() : 0;
    }

    /// Returns the short value of the short tag with the given name, or the default value if no such tag exists.
    public short getShortOrElse(String name, short defaultValue) {
        return get(name) instanceof ShortTag tag ? tag.get() : defaultValue;
    }

    /// Returns the int value of the int tag with the given name.
    ///
    /// @throws NoSuchElementException if no int tag with the given name exists.
    public int getInt(String name) throws NoSuchElementException {
        if (get(name) instanceof IntTag tag) {
            return tag.get();
        } else {
            throw new NoSuchElementException("No int tag with name " + name);
        }
    }

    /// Returns the int value of the int tag with the given name, or `null` if no such tag exists.
    public @Nullable Integer getIntOrNull(String name) {
        return get(name) instanceof IntTag tag ? tag.get() : null;
    }

    /// Returns the int value of the int tag with the given name, or `0` if no such tag exists.
    public int getIntOrZero(String name) {
        return get(name) instanceof IntTag tag ? tag.get() : 0;
    }

    /// Returns the int value of the int tag with the given name, or the default value if no such tag exists.
    public int getIntOrElse(String name, int defaultValue) {
        return get(name) instanceof IntTag tag ? tag.get() : defaultValue;
    }

    /// Returns the long value of the long tag with the given name.
    ///
    /// @throws NoSuchElementException if no long tag with the given name exists.
    public long getLong(String name) throws NoSuchElementException {
        if (get(name) instanceof LongTag tag) {
            return tag.get();
        } else {
            throw new NoSuchElementException("No long tag with name " + name);
        }
    }

    /// Returns the long value of the long tag with the given name, or `null` if no such tag exists.
    public @Nullable Long getLongOrNull(String name) {
        return get(name) instanceof LongTag tag ? tag.get() : null;
    }

    /// Returns the long value of the long tag with the given name, or `0` if no such tag exists.
    public long getLongOrZero(String name) {
        return get(name) instanceof LongTag tag ? tag.get() : 0L;
    }

    /// Returns the long value of the long tag with the given name, or the default value if no such tag exists.
    public long getLongOrElse(String name, long defaultValue) {
        return get(name) instanceof LongTag tag ? tag.get() : defaultValue;
    }

    /// Returns the float value of the float tag with the given name.
    ///
    /// @throws NoSuchElementException if no float tag with the given name exists.
    public float getFloat(String name) throws NoSuchElementException {
        if (get(name) instanceof FloatTag tag) {
            return tag.get();
        } else {
            throw new NoSuchElementException("No float tag with name " + name);
        }
    }

    /// Returns the float value of the float tag with the given name, or `null` if no such tag exists.
    public @Nullable Float getFloatOrNull(String name) {
        return get(name) instanceof FloatTag tag ? tag.get() : null;
    }

    /// Returns the double value of the double tag with the given name.
    ///
    /// @throws NoSuchElementException if no double tag with the given name exists.
    public double getDouble(String name) throws NoSuchElementException {
        if (get(name) instanceof DoubleTag tag) {
            return tag.get();
        } else {
            throw new NoSuchElementException("No double tag with name " + name);
        }
    }

    /// Returns the double value of the double tag with the given name, or `null` if no such tag exists.
    public @Nullable Double getDoubleOrNull(String name) {
        return get(name) instanceof DoubleTag tag ? tag.get() : null;
    }

    /// Returns the string value of the string tag with the given name.
    ///
    /// @throws NoSuchElementException if no string tag with the given name exists.
    public String getString(String name) throws NoSuchElementException {
        if (get(name) instanceof StringTag tag) {
            return tag.get();
        } else {
            throw new NoSuchElementException("No string tag with name " + name);
        }
    }

    /// Returns the string value of the string tag with the given name, or `null` if no such tag exists.
    public @Nullable String getStringOrNull(String name) {
        return get(name) instanceof StringTag tag ? tag.get() : null;
    }

    /// Returns the string value of the string tag with the given name, or an empty string if no such tag exists.
    public String getStringOrEmpty(String name) {
        return get(name) instanceof StringTag tag ? tag.get() : "";
    }

    /// Returns the string value of the string tag with the given name, or the default value if no such tag exists.
    public String getStringOrDefault(String name, String defaultValue) {
        return get(name) instanceof StringTag tag ? tag.get() : defaultValue;
    }

    private void addTag0(Tag tag) {
        assert tag.getParent() == null;

        // If a tag with the same name already exists, remove it first.
        Tag oldTag = subTagsByName.get(tag.getName());
        if (oldTag != null) {
            this.removeElement(oldTag);
        }

        // Set the parent and index of the tag.
        tag.setParent(this, size);

        // Add the tag to the subTags list and subTagsByName map.
        ensureTagsCapacityForAdd();
        tags[size++] = tag;
        subTagsByName.put(tag.getName(), tag);
    }

    /// {@inheritDoc}
    ///
    /// If another tag with the same name already exists, the old tag will be removed.
    @Override
    @Contract(value = "_ -> this", mutates = "this,param1")
    public CompoundTag addTag(Tag tag) {
        if (tag.getParentTag() != null) {
            if (tag.getParentTag() == this) {
                moveTagToLast(tag);
                return this;
            } else {
                // Remove the tag from its old parent.
                tag.getParentTag().removeElement(tag);
            }
        }

        addTag0(tag);
        return this;
    }

    private void addTag0(String name, Tag tag) {
        assert tag.getParent() == null;
        tag.setName0(name);
        addTag0(tag);
    }

    /// Adds a tag with the given name to this compound tag.
    ///
    /// If the tag is already a child of another tag, removes it from the old parent and adds it to this tag.
    ///
    /// If another tag with the same name already exists, the old tag will be removed.
    @Contract(value = "_, _ -> this", mutates = "this,param2")
    public CompoundTag addTag(String name, Tag tag) {
        @SuppressWarnings("unchecked")
        var oldParent = (NBTParent<Tag>) tag.getParent();
        if (oldParent != null) {
            oldParent.removeElement(tag);
        }

        addTag0(name, tag);
        return this;
    }

    /// Adds a byte tag with the given name and value to this compound tag.
    ///
    /// If another tag with the same name already exists, the old tag will be removed.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag addByte(String name, byte value) {
        addTag0(name, new ByteTag(value));
        return this;
    }

    /// Adds a byte tag with the given name and boolean value to this compound tag.
    ///
    /// If another tag with the same name already exists, the old tag will be removed.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag addBoolean(String name, boolean value) {
        addTag0(name, new ByteTag(value));
        return this;
    }

    /// Adds a short tag with the given name and value to this compound tag.
    ///
    /// If another tag with the same name already exists, the old tag will be removed.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag addShort(String name, short value) {
        addTag0(name, new ShortTag(value));
        return this;
    }

    /// Adds an int tag with the given name and value to this compound tag.
    ///
    /// If another tag with the same name already exists, the old tag will be removed.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag addInt(String name, int value) {
        addTag0(name, new IntTag(value));
        return this;
    }

    /// Adds a long tag with the given name and value to this compound tag.
    ///
    /// If another tag with the same name already exists, the old tag will be removed.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag addLong(String name, long value) {
        addTag0(name, new LongTag(value));
        return this;
    }

    /// Adds a float tag with the given name and value to this compound tag.
    ///
    /// If another tag with the same name already exists, the old tag will be removed.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag addFloat(String name, float value) {
        addTag0(name, new FloatTag(value));
        return this;
    }

    /// Adds a double tag with the given name and value to this compound tag.
    ///
    /// If another tag with the same name already exists, the old tag will be removed.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag addDouble(String name, double value) {
        addTag0(name, new DoubleTag(value));
        return this;
    }

    /// Adds a string tag with the given name and value to this compound tag.
    ///
    /// If another tag with the same name already exists, the old tag will be removed.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag addString(String name, String value) {
        addTag0(name, new StringTag(value));
        return this;
    }

    /// Adds a byte array tag with the given name and value to this compound tag.
    ///
    /// If another tag with the same name already exists, the old tag will be removed.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag addByteArray(String name, byte[] value) {
        addTag0(name, new ByteArrayTag(value));
        return this;
    }

    /// Adds an int array tag with the given name and value to this compound tag.
    ///
    /// If another tag with the same name already exists, the old tag will be removed.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag addIntArray(String name, int[] value) {
        addTag0(name, new IntArrayTag(value));
        return this;
    }

    /// Adds a long array tag with the given name and value to this compound tag.
    ///
    /// If another tag with the same name already exists, the old tag will be removed.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag addLongArray(String name, long[] value) {
        addTag0(name, new LongArrayTag(value));
        return this;
    }

    /// Adds an int array tag with the given name and UUID value to this compound tag.
    ///
    /// If another tag with the same name already exists, the old tag will be removed.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag addUUID(String name, UUID value) {
        addTag0(name, new IntArrayTag(value));
        return this;
    }

    @SuppressWarnings("unchecked")
    private <T extends Tag> T getOrPutTag(String name, TagType<T> tagType) throws IllegalStateException {
        Tag existingTag = get(name);
        if (existingTag != null) {
            assert existingTag.getName().equals(name);

            if (existingTag.getType() == tagType) {
                return (T) existingTag;
            } else {
                throw new IllegalStateException("Cannot set a " + tagType + " with name " + name + " because there is already a " + existingTag.getType() + " with the same name");
            }
        } else {
            T tag = tagType.createTag();
            tag.setName0(name);
            addTag(tag);
            return tag;
        }
    }

    /// Sets the byte value of the [ByteTag] with the given name.
    ///
    /// If no byte tag with the given name exists, a new byte tag will be created.
    ///
    /// @throws IllegalStateException if the tag with the given name exists but is not a byte tag.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag setByte(String name, byte value) throws IllegalStateException {
        getOrPutTag(name, TagType.BYTE).set(value);
        return this;
    }

    /// Sets the boolean value of the [ByteTag] with the given name.
    ///
    /// If no byte tag with the given name exists, a new byte tag will be created.
    ///
    /// @throws IllegalStateException if the tag with the given name exists but is not a byte tag.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag setBoolean(String name, boolean value) throws IllegalStateException {
        getOrPutTag(name, TagType.BYTE).setBoolean(value);
        return this;
    }

    /// Sets the short value of the [ShortTag] with the given name.
    ///
    /// If no short tag with the given name exists, a new short tag will be created.
    ///
    /// @throws IllegalStateException if the tag with the given name exists but is not a short tag.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag setShort(String name, short value) throws IllegalStateException {
        getOrPutTag(name, TagType.SHORT).set(value);
        return this;
    }

    /// Sets the int value of the [IntTag] with the given name.
    ///
    /// If no int tag with the given name exists, a new int tag will be created.
    ///
    /// @throws IllegalStateException if the tag with the given name exists but is not an int tag.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag setInt(String name, int value) throws IllegalStateException {
        getOrPutTag(name, TagType.INT).set(value);
        return this;
    }

    /// Sets the long value of the [LongTag] with the given name.
    ///
    /// If no long tag with the given name exists, a new long tag will be created.
    ///
    /// @throws IllegalStateException if the tag with the given name exists but is not a long tag.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag setLong(String name, long value) throws IllegalStateException {
        getOrPutTag(name, TagType.LONG).set(value);
        return this;
    }

    /// Sets the float value of the [FloatTag] with the given name.
    ///
    /// If no float tag with the given name exists, a new float tag will be created.
    ///
    /// @throws IllegalStateException if the tag with the given name exists but is not a float tag.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag setFloat(String name, float value) throws IllegalStateException {
        getOrPutTag(name, TagType.FLOAT).set(value);
        return this;
    }

    /// Sets the double value of the [DoubleTag] with the given name.
    ///
    /// If no double tag with the given name exists, a new double tag will be created.
    ///
    /// @throws IllegalStateException if the tag with the given name exists but is not a double tag.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag setDouble(String name, double value) throws IllegalStateException {
        getOrPutTag(name, TagType.DOUBLE).set(value);
        return this;
    }

    /// Sets the string value of the [StringTag] with the given name.
    ///
    /// If no string tag with the given name exists, a new string tag will be created.
    ///
    /// @throws IllegalStateException if the tag with the given name exists but is not a string tag.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag setString(String name, String value) throws IllegalStateException {
        getOrPutTag(name, TagType.STRING).set(value);
        return this;
    }

    /// Sets the byte array value of the [ByteArrayTag] with the given name.
    ///
    /// If no byte array tag with the given name exists, a new byte array tag will be created.
    ///
    /// @throws IllegalStateException if the tag with the given name exists but is not a byte array tag.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag setByteArray(String name, byte[] value) throws IllegalStateException {
        getOrPutTag(name, TagType.BYTE_ARRAY).setAll(value);
        return this;
    }

    /// Sets the int array value of the [IntArrayTag] with the given name.
    ///
    /// If no int array tag with the given name exists, a new int array tag will be created.
    ///
    /// @throws IllegalStateException if the tag with the given name exists but is not an int array tag.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag setIntArray(String name, int[] value) throws IllegalStateException {
        getOrPutTag(name, TagType.INT_ARRAY).setAll(value);
        return this;
    }

    /// Sets the long array value of the [LongArrayTag] with the given name.
    ///
    /// If no long array tag with the given name exists, a new long array tag will be created.
    ///
    /// @throws IllegalStateException if the tag with the given name exists but is not a long array tag.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag setLongArray(String name, long[] value) throws IllegalStateException {
        getOrPutTag(name, TagType.LONG_ARRAY).setAll(value);
        return this;
    }

    /// Set the UUID value of the [IntArrayTag] with the given name.
    ///
    /// If no int array tag with the given name exists, a new int array tag will be created.
    ///
    /// @throws IllegalStateException if the tag with the given name exists but is not an int array tag.
    @Contract(value = "_, _ -> this", mutates = "this")
    public CompoundTag setUUID(String name, UUID value) throws IllegalStateException {
        getOrPutTag(name, TagType.INT_ARRAY).setUUID(value);
        return this;
    }

    @Override
    public Tag removeTagAt(int index) throws IndexOutOfBoundsException {
        Objects.checkIndex(index, size);

        Tag tag = removeTagFromArray(index);
        assert tag.getIndex() == index && tag.getParentTag() == this;

        // Clear the tag's parent and index.
        tag.setParent(null, -1);

        // Decrease the size.
        size--;

        // Remove the tag from the subTagsByName map.
        Tag removedFromMap = subTagsByName.remove(tag.getName());
        if (removedFromMap != tag) {
            throw new AssertionError("Expected " + tag + ", but got " + removedFromMap);
        }

        // Update the index of the successor tags.
        updateIndexes(index);

        return tag;
    }

    @Override
    @Contract(mutates = "this")
    public void clear() {
        super.clear();
        subTagsByName.clear();
    }

    @Override
    void readContent(DataReader reader) throws IOException {
        int count = 0;

        Tag subTag;
        while ((subTag = NBTInput.readTag(reader)) != null) {
            count++;
            addTag(subTag);
        }

        if (count != this.size()) {
            throw new IOException("Duplicate subtags found in compound tag");
        }
    }

    @Override
    void writeContent(DataWriter writer) throws IOException {
        for (int i = 0; i < size; i++) {
            NBTOutput.writeTag(writer, tags[i]);
        }
        writer.writeByte((byte) 0x00);
    }

    @Override
    @Contract(pure = true)
    public int contentHashCode() {
        return subTagsByName.hashCode();
    }

    @Override
    @Contract(pure = true)
    public boolean contentEquals(Tag other) {
        return other instanceof CompoundTag that && subTagsByName.equals(that.subTagsByName);
    }

    @Override
    @Contract(value = "-> new", pure = true)
    public CompoundTag clone() {
        var newTag = new CompoundTag();
        newTag.setName0(this.name);
        for (int i = 0; i < size; i++) {
            newTag.addTag(tags[i].clone());
        }
        return newTag;
    }
}
