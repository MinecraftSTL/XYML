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
import org.glavo.nbt.internal.ArrayAccessor;
import org.intellij.lang.annotations.Flow;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.UnknownNullability;

import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/// Base class for tags that can contain other tags as children.
///
/// These are the all possible types of parent tags:
///
/// - [CompoundTag]: A tag that holds a collection of named tags.
/// - [ListTag]: A tag that holds a collection of unnamed tags.
/// - [ArrayTag]: A tag that holds an array of primitive values.
///     - [ByteArrayTag]: A tag that holds an array of [byte tags][ByteTag].
///     - [IntArrayTag]: A tag that holds an array of [int tags][IntTag]. Sometimes used for UUIDs.
///     - [LongArrayTag]: A tag that holds an array of [long tags][LongTag].
///
/// @see Tag
/// @see CompoundTag
/// @see ListTag
/// @see ArrayTag
public sealed abstract class ParentTag<T extends Tag> extends Tag
        implements NBTParent<T>, Iterable<T>
        permits CompoundTag, ListTag, ArrayTag {

    private final Tag[] EMPTY_TAGS = new Tag[0];

    // Store all sub-tags in an array.
    //
    // For ListTag and CompoundTag, the array length is large or equal to the size,
    // and all tags in [0, size) are not null.
    //
    // For ArrayTag, we may lazy allocate the array and the tags, so the array length
    // may be smaller than the size, and some tags in [0, size) may be null.
    @UnknownNullability
    Tag[] tags = EMPTY_TAGS;
    int size;

    ParentTag() {
    }

    /// Prepares to update the name of the given subtag.
    ///
    /// Used internally by [Tag#setName(String)].
    ///
    /// @see Tag#setName(String)
    abstract void preUpdateSubTagName(Tag tag, String oldName, String newName) throws IllegalArgumentException;

    final void ensureTagsCapacity(int minCapacity) {
        if (minCapacity > tags.length) {
            tags = Arrays.copyOf(tags, ArrayAccessor.nextCapacity(tags.length, minCapacity));
        }
    }

    final void ensureTagsCapacityForAdd() {
        if (size >= tags.length) {
            tags = Arrays.copyOf(tags, ArrayAccessor.nextCapacity(tags.length, size + 1));
        }
    }

    /// Removes the tag at the given index from the array, and decreases the size.
    ///
    /// @return The old tag at the given index.
    final @UnknownNullability T removeTagFromArray(int index) {
        assert index >= 0 && index < size;

        if (index >= tags.length) {
            return null;
        }

        @SuppressWarnings("unchecked")
        T oldTag = (T) tags[index];

        int arrayEnd = Math.min(size, tags.length);
        if (index < arrayEnd - 1) {
            System.arraycopy(tags, index + 1, tags, index, arrayEnd - index - 1);
            tags[arrayEnd - 1] = null;
        } else if (oldTag != null) {
            tags[index] = null;
        }

        return oldTag;
    }

    /// Updates the indexes of the subtags starting from the given index.
    final void updateIndexes(int startIndex) {
        for (int i = startIndex, end = Math.min(size, tags.length); i < end; i++) {
            Tag subTag = tags[i];
            if (subTag != null) {
                subTag.setIndex(i);
            }
        }
    }

    @Override
    public abstract TagType<? extends ParentTag<?>> getType();

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public ParentTag<T> setName(String name) throws IllegalArgumentException {
        setName0(name);
        return this;
    }

    /// Returns `true` if this tag has no subtags, `false` otherwise.
    @Override
    @Contract(pure = true)
    public final boolean isEmpty() {
        return size == 0;
    }

    /// Returns the number of subtags in this tag.
    @Override
    @Contract(pure = true)
    public final int size() {
        return size;
    }

    /// Returns the subtag at the given index.
    ///
    /// @throws IndexOutOfBoundsException if the index is out of bounds.
    @Contract(pure = true)
    @Flow(sourceIsContainer = true)
    public T getTag(int index) throws IndexOutOfBoundsException {
        Objects.checkIndex(index, size);
        @SuppressWarnings("unchecked")
        T tag = (T) tags[index];
        return tag;
    }

    final void moveTagToLast(T tag) {
        assert tag.getParent() == this;

        int index = tag.getIndex();

        if (tag.getIndex() == this.size() - 1) {
            // The tag is already the last child of this tag, so we don't need to do anything.

            assert tag == tags[index];
        } else {
            // Move the tag to the end of the subTags list.

            Tag oldTag = removeTagFromArray(index);
            if (oldTag != tag) {
                throw new AssertionError("Expected " + tag + ", but got " + oldTag);
            }

            tags[size - 1] = tag;

            updateIndexes(index);
        }
    }

    /// Adds the `tag` to this tag.
    ///
    /// If the `tag` is already a child of this tag, move it to the end of the list.
    ///
    /// If the `tag` is already a child of another tag, removes it from old parent and adds it to this tag.
    @Contract(value = "_ -> this", mutates = "this,param1")
    public abstract ParentTag<T> addTag(@Flow(targetIsContainer = true)
                                        T tag) throws IllegalArgumentException;

    /// Adds all `tags` to this tag.
    ///
    /// @see #addTag(Tag)
    public final void addTags(@Flow(sourceIsContainer = true, targetIsContainer = true)
                              Iterable<? extends T> tags) throws IllegalArgumentException {
        if (this == tags) {
            return;
        }

        for (T tag : tags) {
            this.addTag(tag);
        }
    }

    /// Adds all `tags` to this tag.
    ///
    /// @see #addTag(Tag)
    @SafeVarargs
    public final void addTags(@Flow(sourceIsContainer = true, targetIsContainer = true)
                              T... tags) throws IllegalArgumentException {
        for (T tag : tags) {
            this.addTag(tag);
        }
    }

    /// Removes the tag at the given index from this tag.
    ///
    /// @param index The index of the tag to remove.
    /// @throws IndexOutOfBoundsException if the index is out of bounds.
    @Contract(mutates = "this")
    public void removeAt(int index) throws IndexOutOfBoundsException {
        removeTagAt(index);
    }

    /// Removes the tag at the given index from this tag.
    ///
    /// If the return value is not needed, use [ParentTag#removeAt(int)] instead.
    ///
    /// @param index The index of the tag to remove.
    /// @return The removed tag.
    /// @throws IndexOutOfBoundsException if the index is out of bounds.
    @Contract(mutates = "this")
    @SuppressWarnings("UnusedReturnValue")
    public abstract T removeTagAt(int index) throws IndexOutOfBoundsException;

    /// Removes the `tag` from this tag.
    ///
    /// @throws IllegalArgumentException if the `tag` is not a child of this tag.
    @Contract(mutates = "this,param1")
    public void removeTag(Tag tag) throws IllegalArgumentException {
        if (tag.getParentTag() != this) {
            throw new IllegalArgumentException("The tag is not a child of this tag");
        }

        assert tag.getIndex() >= 0 && tag.getIndex() < size;

        removeAt(tag.getIndex());
    }

    /// @see #removeTag(Tag)
    @ApiStatus.Obsolete
    @Override
    public final void removeElement(Tag tag) throws IllegalArgumentException {
        removeTag(tag);
    }

    /// Removes all subtags from this tag.
    @Contract(mutates = "this")
    public void clear() {
        for (int i = 0, end = Math.min(size, tags.length); i < end; i++) {
            Tag subTag = tags[i];
            if (subTag != null) {
                subTag.setParent(null, -1);
            }
        }

        tags = EMPTY_TAGS;
        size = 0;
    }

    @Override
    public Iterator<T> iterator() {
        if (size == 0) {
            return Collections.emptyIterator();
        }

        return new Iterator<>() {
            private int cursor = 0;

            @Override
            public boolean hasNext() {
                return cursor < size;
            }

            @Override
            public T next() {
                if (cursor >= size) {
                    throw new NoSuchElementException();
                }
                return getTag(cursor++);
            }
        };
    }

    /// Returns a stream of subtags.
    @Override
    public final Stream<T> stream() {
        return StreamSupport.stream(Spliterators.spliterator(iterator(), size(), 0), false);
    }

    @Override
    @Contract(value = "-> new", pure = true)
    public abstract ParentTag<T> clone();
}
