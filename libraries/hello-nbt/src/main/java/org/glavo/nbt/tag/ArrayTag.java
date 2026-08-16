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

import org.glavo.nbt.internal.ArrayAccessor;
import org.intellij.lang.annotations.Flow;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.MustBeInvokedByOverriders;
import org.jetbrains.annotations.Nullable;

import java.nio.Buffer;
import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.BaseStream;

/// Base class for array tags. Each array tag holds an array of numbers.
///
/// Array tags are subclasses of [ParentTag], and each element is a [ValueTag]. In practice,
/// this array will lazily allocate [ValueTag] for its elements as much as possible,
/// making the performance and memory overhead of this class close to that of primitive type arrays in most cases.
///
/// These are the all possible types of array tags:
///
/// - [ByteArrayTag]: A tag that holds an array of [byte tags][ByteTag].
/// - [IntArrayTag]: A tag that holds an array of [int tags][IntTag] or a UUID.
/// - [LongArrayTag]: A tag that holds an array of [long tags][LongTag].
///
/// @param <E> the type of number elements in this array
/// @param <T> the type of subtags in this array
/// @param <A> the type of the array
/// @param <B> the type of the buffer
/// @see Tag
/// @see ParentTag
/// @see ByteArrayTag
/// @see IntArrayTag
/// @see LongArrayTag
public sealed abstract class ArrayTag<E extends Number, T extends ValueTag<E>, A, B extends Buffer>
        extends ParentTag<T>
        permits ByteArrayTag, IntArrayTag, LongArrayTag {

    A values = accessor().empty();

    ArrayTag() {
    }

    @Contract(pure = true)
    abstract ArrayAccessor<E, T, A, B> accessor();

    private void removeValueFromArray(int index) {
        assert index >= 0 && index < size;

        if (index < size - 1) {
            //noinspection SuspiciousSystemArraycopy
            System.arraycopy(values, index + 1, values, index, size - index - 1);
        }
    }

    final void ensureValuesCapacityForAdd() {
        if (accessor().getLength(values) == size) {
            values = accessor().copyOf(values, ArrayAccessor.nextCapacity(size, size + 1));
        }
    }

    @Override
    final void preUpdateSubTagName(Tag tag, String oldName, String newName) throws IllegalArgumentException {
        if (!newName.isEmpty()) {
            throw new IllegalArgumentException("The name of the subtag must be null for ArrayTag");
        }
    }

    @Override
    @Contract(pure = true)
    public abstract TagType<? extends ArrayTag<E, T, A, B>> getType();

    /// Returns the type of the elements in this array.
    @Contract(pure = true)
    public abstract TagType<T> getElementType();

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public ArrayTag<E, T, A, B> setName(String name) throws IllegalArgumentException {
        setName0(name);
        return this;
    }

    private @Nullable List<E> listView = null;

    /// Returns a view of the values of this array as a list.
    ///
    /// @apiNote Currently, this list supports most list operations, but does not yet support
    /// operations such as [List#add(int, Object)] for inserting at a specific index.
    @Contract(pure = true)
    public List<E> values() {
        if (listView == null) {
            listView = new AbstractList<>() {
                @Override
                public int size() {
                    return size;
                }

                @Override
                public E get(int index) {
                    return getValue(index);
                }

                @Override
                public E set(int index, E element) {
                    E oldValue = getValue(index);
                    ArrayTag.this.set(index, element);
                    return oldValue;
                }

                @Override
                public boolean add(E e) {
                    ArrayTag.this.add(e);
                    return true;
                }

                @Override
                public E remove(int index) {
                    E oldValue = getValue(index);
                    ArrayTag.this.removeAt(index);
                    return oldValue;
                }

                @Override
                public void clear() {
                    ArrayTag.this.clear();
                }
            };
        }
        return listView;
    }

    /// Returns an iterator over the elements of this array.
    public abstract Iterator<E> valueIterator();

    /// Returns a sequential stream with this array as its source.
    @Contract(pure = true)
    public abstract BaseStream<E, ?> valueStream();

    /// Returns the clone of the array.
    @Contract(pure = true)
    public final A getArray() {
        return size > 0 ? accessor().copyOf(values, size) : accessor().empty();
    }

    final @Nullable T getTagOrNull(int index) {
        if (index >= tags.length) {
            return null;
        }
        @SuppressWarnings("unchecked")
        T tag = (T) tags[index];
        return tag;
    }

    @Override
    @Contract(pure = true)
    @Flow(sourceIsContainer = true)
    public T getTag(int index) throws IndexOutOfBoundsException {
        Objects.checkIndex(index, size);

        T tag = getTagOrNull(index);
        if (tag != null) {
            return tag;
        }

        tag = accessor().newTagFromElement(values, index);
        ensureTagsCapacity(index + 1);
        assert tags[index] == null;

        tag.setParent(this, index);
        tags[index] = tag;
        return tag;
    }

    /// Returns the element at the given index.
    ///
    /// For specific subclasses, methods such as [ByteArrayTag#get(int)],
    /// [IntArrayTag#get(int)], [LongArrayTag#get(int)] can be used to get unboxed elements.
    ///
    /// @throws IndexOutOfBoundsException if the index is out of bounds.
    /// @see ByteArrayTag#get(int)
    /// @see IntArrayTag#get(int)
    /// @see LongArrayTag#get(int)
    @Contract(pure = true)
    @Flow(sourceIsContainer = true)
    public final E getValue(int index) throws IndexOutOfBoundsException {
        Objects.checkIndex(index, size);
        return accessor().get(values, index);
    }

    /// Returns the element at the given index as a string.
    ///
    /// @throws IndexOutOfBoundsException if the index is out of bounds.
    @Contract(pure = true)
    public final String getAsString(int index) throws IndexOutOfBoundsException {
        Objects.checkIndex(index, size);
        return accessor().getAsString(values, index);
    }

    /// Returns the array as a readonly [Buffer].
    ///
    /// The buffer is readonly, the position is set to `0`, and the limit is set to the size of the array.
    ///
    /// Each call returns a new buffer, but the underlying implementation may share the same array.
    @Contract(value = "-> new", pure = true)
    @Flow(sourceIsContainer = true, targetIsContainer = true)
    public final B getBuffer() {
        return accessor().getReadOnlyView(values, 0, size);
    }

    /// Sets the value of the tag without cloning the array.
    @Contract(mutates = "this")
    final void setArrayWithoutClone(A array, int size) {
        assert accessor().getLength(array) <= size;

        clear();

        this.values = array;
        this.size = size;
    }

    /// Sets the value at the given index.
    ///
    /// @throws IndexOutOfBoundsException if the index is out of bounds.
    @Contract(value = "_, _ -> this", mutates = "this")
    public abstract ArrayTag<E, T, A, B> set(int index, @Flow(targetIsContainer = true) E value) throws IndexOutOfBoundsException;

    /// Sets all values of the tag from an array.
    ///
    /// The array is cloned to avoid external modifications.
    ///
    /// Calling this method will clear the current array, all subtags will be removed.
    @Contract(value = "_ -> this", mutates = "this")
    @MustBeInvokedByOverriders
    public ArrayTag<E, T, A, B> setAll(@Flow(sourceIsContainer = true, targetIsContainer = true)
                       A array) {
        clear();

        int newSize = accessor().getLength(array);
        if (newSize > 0) {
            this.values = accessor().copyOf(array, newSize);
            this.size = newSize;
        }
        return this;
    }

    /// Set all values of the tag from a buffer.
    ///
    /// This method uses the data in the buffer from `position` to `limit` to set the values of the array.
    /// After calling this method, the `position` of the buffer will be set to `limit`.
    ///
    /// Calling this method will clear the current array, all subtags will be removed.
    @Contract(value = "_ -> this", mutates = "this,param1")
    public ArrayTag<E, T, A, B> setAll(@Flow(sourceIsContainer = true, targetIsContainer = true)
                             B buffer) {
        clear();

        if (buffer.hasRemaining()) {
            A array = accessor().get(buffer);
            setArrayWithoutClone(array, accessor().getLength(array));
        }
        return this;
    }

    /// Appends the specified value to the end of this array.
    @Contract(value = "_ -> this", mutates = "this")
    public abstract ArrayTag<E, T, A, B> add(@Flow(targetIsContainer = true)
                                             E value);

    @Override
    @MustBeInvokedByOverriders
    @Contract(value = "_ -> this", mutates = "this,param1")
    public ArrayTag<E, T, A, B> addTag(@Flow(targetIsContainer = true)
                                       T tag) throws IllegalArgumentException {
        if (tag.getParentTag() != null) {
            if (tag.getParentTag() == this) {
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

                    removeValueFromArray(index);

                    ensureTagsCapacity(size);
                    tags[size - 1] = tag;
                    accessor().set(values, size - 1, tag);

                    updateIndexes(index);
                }

                return this;
            } else {
                // Remove the tag from its old parent.
                tag.getParentTag().removeElement(tag);
            }
        }

        tag.setName0("");

        ensureTagsCapacityForAdd();

        add(tag.getValue());
        tag.setParent(this, size - 1);
        tags[size - 1] = tag;

        return this;
    }

    @Override
    @Contract(mutates = "this")
    public final void removeAt(int index) throws IndexOutOfBoundsException {
        Objects.checkIndex(index, size);

        T tag = removeTagFromArray(index);
        if (tag != null) {
            assert tag.getIndex() == index && tag.getParentTag() == this;

            tag.setParent(null, -1);
        }

        removeValueFromArray(index);

        size--;
        updateIndexes(index);
    }

    @Override
    @Contract(mutates = "this")
    public final T removeTagAt(int index) throws IndexOutOfBoundsException {
        Objects.checkIndex(index, size);

        T tag = removeTagFromArray(index);

        if (tag != null) {
            assert tag.getIndex() == index && tag.getParentTag() == this;

            tag.setParent(null, -1);
        } else {
            tag = accessor().newTagFromElement(values, index);
        }

        removeValueFromArray(index);

        size--;
        updateIndexes(index);
        return tag;
    }

    @Override
    public final void clear() {
        super.clear();
        values = accessor().empty();
    }

    @Override
    @Contract(value = "-> new", pure = true)
    public abstract ArrayTag<E, T, A, B> clone();

    @Override
    public final int contentHashCode() {
        return accessor().hashCode(values, 0, size);
    }

    @Override
    public final boolean contentEquals(Tag other) {
        if (this.getClass() == other.getClass()) {
            @SuppressWarnings("unchecked")
            var that = (ArrayTag<E, T, A, B>) other;
            return this.size == that.size && accessor().equals(values, 0, that.values, 0, size);
        } else {
            return false;
        }
    }
}
