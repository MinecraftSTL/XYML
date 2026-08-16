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
package org.glavo.nbt;

import org.glavo.nbt.chunk.Chunk;
import org.glavo.nbt.chunk.ChunkRegion;
import org.glavo.nbt.internal.path.NBTPathImpl;
import org.glavo.nbt.tag.*;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.NoSuchElementException;
import java.util.stream.Stream;

/// Base interface for NBT elements that can contain other NBT elements as children.
///
/// @see ParentTag
/// @see ChunkRegion
/// @see Chunk
public sealed interface NBTParent<E extends NBTElement> extends NBTElement, Iterable<E>
        permits ParentTag, ChunkRegion, Chunk {

    /// Returns `true` if this parent has no child elements, `false` otherwise.
    @Contract(pure = true)
    boolean isEmpty();

    /// Returns the number of child elements.
    @Contract(pure = true)
    int size();

    /// Returns a stream of child elements.
    @Contract(pure = true)
    Stream<E> stream();

    /// Returns a stream of child elements that match the given path.
    @Contract(pure = true)
    default <T extends Tag> Stream<T> getAllTags(NBTPath<? extends T> path) {
        return NBTPathImpl.select(this, path);
    }

    /// Returns a stream of child elements that match the given path.
    ///
    /// @throws NoSuchElementException if no element matches the given path.
    @Contract(pure = true)
    default <T extends Tag> T getFirstTag(NBTPath<? extends T> path) throws NoSuchElementException {
        return getAllTags(path).findFirst().orElseThrow(NoSuchElementException::new);
    }

    /// Returns the first child element that matches the given path, or `null` if no element matches.
    @Contract(pure = true)
    default <T extends Tag> @Nullable T getFirstTagOrNull(NBTPath<? extends T> path) {
        return getAllTags(path).findFirst().orElse(null);
    }

    /// Returns the value of the first child element that matches the given path.
    ///
    /// @throws NoSuchElementException if no element matches the given path.
    @Contract(pure = true)
    default <V, T extends ValueTag<? extends V>> V getFirstValue(NBTPath<? extends T> path) throws NoSuchElementException {
        return getFirstTag(path).getValue();
    }

    /// Returns the value of the first child element that matches the given path, or `null` if no element matches.
    @Contract(pure = true)
    default <V, T extends ValueTag<? extends V>> @Nullable V getFirstValueOrNull(NBTPath<? extends T> path) {
        ValueTag<? extends V> tag = getFirstTagOrNull(path);
        return tag != null ? tag.getValue() : null;
    }

    /// Returns the value of the first child element that matches the given path, or `defaultValue` if no element matches.
    @Contract(pure = true)
    default <V, T extends ValueTag<? extends V>> V getFirstValueOrDefault(NBTPath<? extends T> path, V defaultValue) {
        ValueTag<? extends V> tag = getFirstTagOrNull(path);
        return tag != null ? tag.getValue() : defaultValue;
    }

    /// Returns the value of the first child element that matches the given path.
    ///
    /// @throws NoSuchElementException if no element matches the given path.
    @Contract(pure = true)
    default byte getFirstByte(NBTPath<? extends ByteTag> path) throws NoSuchElementException {
        return getFirstTag(path).getValue();
    }

    /// Returns the value of the first child element that matches the given path, or `null` if no element matches.
    @Contract(pure = true)
    default @Nullable Byte getFirstByteOrNull(NBTPath<? extends ByteTag> path) {
        ByteTag tag = getFirstTagOrNull(path);
        return tag != null ? tag.getValue() : null;
    }

    /// Returns the value of the first child element that matches the given path, or `defaultValue` if no element matches.
    @Contract(pure = true)
    default byte getFirstByteOrDefault(NBTPath<? extends ByteTag> path, byte defaultValue) {
        ByteTag tag = getFirstTagOrNull(path);
        return tag != null ? tag.getValue() : defaultValue;
    }

    /// Returns the value of the first child element that matches the given path.
    ///
    /// @throws NoSuchElementException if no element matches the given path.
    @Contract(pure = true)
    default short getFirstShort(NBTPath<? extends ShortTag> path) throws NoSuchElementException {
        return getFirstTag(path).getValue();
    }

    /// Returns the value of the first child element that matches the given path, or `null` if no element matches.
    @Contract(pure = true)
    default @Nullable Short getFirstShortOrNull(NBTPath<? extends ShortTag> path) {
        ShortTag tag = getFirstTagOrNull(path);
        return tag != null ? tag.getValue() : null;
    }

    /// Returns the value of the first child element that matches the given path, or `defaultValue` if no element matches.
    @Contract(pure = true)
    default short getFirstShortOrDefault(NBTPath<? extends ShortTag> path, short defaultValue) {
        ShortTag tag = getFirstTagOrNull(path);
        return tag != null ? tag.getValue() : defaultValue;
    }

    /// Returns the value of the first child element that matches the given path.
    ///
    /// @throws NoSuchElementException if no element matches the given path.
    @Contract(pure = true)
    default int getFirstInt(NBTPath<? extends IntTag> path) throws NoSuchElementException {
        return getFirstTag(path).getValue();
    }

    /// Returns the value of the first child element that matches the given path, or `null` if no element matches.
    @Contract(pure = true)
    default @Nullable Integer getFirstIntOrNull(NBTPath<? extends IntTag> path) {
        IntTag tag = getFirstTagOrNull(path);
        return tag != null ? tag.getValue() : null;
    }

    /// Returns the value of the first child element that matches the given path, or `defaultValue` if no element matches.
    @Contract(pure = true)
    default int getFirstIntOrDefault(NBTPath<? extends IntTag> path, int defaultValue) {
        IntTag tag = getFirstTagOrNull(path);
        return tag != null ? tag.getValue() : defaultValue;
    }

    /// Returns the value of the first child element that matches the given path.
    ///
    /// @throws NoSuchElementException if no element matches the given path.
    @Contract(pure = true)
    default long getFirstLong(NBTPath<? extends LongTag> path) throws NoSuchElementException {
        return getFirstTag(path).getValue();
    }

    /// Returns the value of the first child element that matches the given path, or `null` if no element matches.
    @Contract(pure = true)
    default @Nullable Long getFirstLongOrNull(NBTPath<? extends LongTag> path) {
        LongTag tag = getFirstTagOrNull(path);
        return tag != null ? tag.getValue() : null;
    }

    /// Returns the value of the first child element that matches the given path, or `defaultValue` if no element matches.
    @Contract(pure = true)
    default long getFirstLongOrDefault(NBTPath<? extends LongTag> path, long defaultValue) {
        LongTag tag = getFirstTagOrNull(path);
        return tag != null ? tag.getValue() : defaultValue;
    }

    /// Returns the value of the first child element that matches the given path.
    ///
    /// @throws NoSuchElementException if no element matches the given path.
    @Contract(pure = true)
    default float getFirstFloat(NBTPath<? extends FloatTag> path) throws NoSuchElementException {
        return getFirstTag(path).getValue();
    }

    /// Returns the value of the first child element that matches the given path, or `null` if no element matches.
    @Contract(pure = true)
    default @Nullable Float getFirstFloatOrNull(NBTPath<? extends FloatTag> path) {
        FloatTag tag = getFirstTagOrNull(path);
        return tag != null ? tag.getValue() : null;
    }

    /// Returns the value of the first child element that matches the given path, or `defaultValue` if no element matches.
    @Contract(pure = true)
    default float getFirstFloatOrDefault(NBTPath<? extends FloatTag> path, float defaultValue) {
        FloatTag tag = getFirstTagOrNull(path);
        return tag != null ? tag.getValue() : defaultValue;
    }

    /// Returns the value of the first child element that matches the given path.
    ///
    /// @throws NoSuchElementException if no element matches the given path.
    @Contract(pure = true)
    default double getFirstDouble(NBTPath<? extends DoubleTag> path) throws NoSuchElementException {
        return getFirstTag(path).getValue();
    }

    /// Returns the value of the first child element that matches the given path, or `null` if no element matches.
    @Contract(pure = true)
    default @Nullable Double getFirstDoubleOrNull(NBTPath<? extends DoubleTag> path) {
        DoubleTag tag = getFirstTagOrNull(path);
        return tag != null ? tag.getValue() : null;
    }

    /// Returns the value of the first child element that matches the given path, or `defaultValue` if no element matches.
    @Contract(pure = true)
    default double getFirstDoubleOrDefault(NBTPath<? extends DoubleTag> path, double defaultValue) {
        DoubleTag tag = getFirstTagOrNull(path);
        return tag != null ? tag.getValue() : defaultValue;
    }

    /// Returns the value of the first child element that matches the given path.
    ///
    /// @throws NoSuchElementException if no element matches the given path.
    @Contract(pure = true)
    default String getFirstString(NBTPath<? extends StringTag> path) throws NoSuchElementException {
        return getFirstTag(path).getValue();
    }

    /// Returns the value of the first child element that matches the given path, or `null` if no element matches.
    @Contract(pure = true)
    default @Nullable String getFirstStringOrNull(NBTPath<? extends StringTag> path) {
        StringTag tag = getFirstTagOrNull(path);
        return tag != null ? tag.getValue() : null;
    }

    /// Returns the value of the first child element that matches the given path, or `defaultValue` if no element matches.
    @Contract(pure = true)
    default String getFirstStringOrDefault(NBTPath<? extends StringTag> path, String defaultValue) {
        StringTag tag = getFirstTagOrNull(path);
        return tag != null ? tag.getValue() : defaultValue;
    }

    /// Removes the `element` from this parent.
    ///
    /// @throws IllegalArgumentException if the `element` is not a child of this parent.
    @Contract(mutates = "this,param1")
    void removeElement(E element) throws IllegalArgumentException;

    @Override
    @Contract(value = "-> new", pure = true)
    NBTParent<E> clone();
}
