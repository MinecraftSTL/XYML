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
package org.glavo.nbt.chunk;

import org.glavo.nbt.NBTElement;
import org.glavo.nbt.NBTParent;
import org.glavo.nbt.internal.ChunkUtils;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Stream;

/// Represents a chunk region in an Anvil file (`.mca`) or region file (`.mcr`).
///
/// Each chunk region contains 32 chunks in the x direction and 32 chunks in the z direction, totaling 1024 chunks.
///
/// @see <a href="https://minecraft.wiki/w/Anvil_file_format">Anvil file format - Minecraft Wiki</a>
/// @see <a href="https://minecraft.wiki/w/Region_file_format">Region file format - Minecraft Wiki</a>
public final class ChunkRegion implements NBTParent<Chunk>, NBTElement, Iterable<Chunk> {

    private final @Nullable Chunk[] chunks = new Chunk[ChunkUtils.CHUNKS_PRE_REGION];

    /// Creates a new empty chunk region.
    ///
    /// All chunks in the region are initially empty.
    public ChunkRegion() {
    }

    /// Always returns `null`. A chunk region is the root of the NBT tree, and it has no parent.
    @Override
    @Contract(value = "-> null", pure = true)
    public @Nullable NBTParent<ChunkRegion> getParent() {
        return null;
    }

    /// Always returns `false`. A chunk region always has 32x32 child chunks.
    @Override
    public boolean isEmpty() {
        return false;
    }

    /// Always returns 1024. A chunk region always has 32x32 child chunks.
    @Override
    public int size() {
        return ChunkUtils.CHUNKS_PRE_REGION;
    }

    /// Returns the chunk at the given local index.
    ///
    /// @throws IndexOutOfBoundsException if the local index is out of range.
    @Contract(pure = true)
    public Chunk getChunk(int localIndex) {
        Objects.checkIndex(localIndex, ChunkUtils.CHUNKS_PRE_REGION);
        Chunk chunk = chunks[localIndex];
        if (chunk == null) {
            chunks[localIndex] = chunk = new Chunk(this, localIndex);
        }
        return chunk;
    }

    /// Returns the chunk at the given local coordinates.
    ///
    /// @throws IndexOutOfBoundsException if the local coordinates are out of range.
    @Contract(pure = true)
    public Chunk getChunk(int localX, int localZ) {
        Objects.checkIndex(localX, ChunkUtils.CHUNKS_PER_REGION_SIDE);
        Objects.checkIndex(localZ, ChunkUtils.CHUNKS_PER_REGION_SIDE);

        return getChunk(ChunkUtils.toLocalIndex(localX, localZ));
    }

    /// Sets the chunk at the given local index.
    ///
    /// @throws IndexOutOfBoundsException if the local index is out of range.
    @Contract(value = "_, _ -> this", mutates = "this,param2")
    public ChunkRegion setChunk(int localIndex, Chunk chunk) {
        Objects.checkIndex(localIndex, ChunkUtils.CHUNKS_PRE_REGION);
        Objects.requireNonNull(chunk);

        Chunk old = chunks[localIndex];
        if (old != null) {
            old.setParent(null, -1);
        }

        if (chunk.getParent() != null) {
            // The chunk is already in another region, so we need to remove it from its old region first.
            ChunkRegion oldRegion = chunk.getParent();
            oldRegion.removeElement(chunk);
        }

        chunk.setParent(this, localIndex);
        chunks[localIndex] = chunk;
        return this;
    }

    /// Sets the chunk at the given local coordinates.
    ///
    /// @throws IndexOutOfBoundsException if the local coordinates are out of range.
    @Contract(value = "_, _, _ -> this", mutates = "this,param3")
    public ChunkRegion setChunk(int localX, int localZ, Chunk chunk) {
        Objects.checkIndex(localX, ChunkUtils.CHUNKS_PER_REGION_SIDE);
        Objects.checkIndex(localZ, ChunkUtils.CHUNKS_PER_REGION_SIDE);
        setChunk(ChunkUtils.toLocalIndex(localX, localZ), chunk);
        return this;
    }

    @Override
    public Iterator<Chunk> iterator() {
        return new Iterator<>() {
            private int cursor;

            @Override
            public boolean hasNext() {
                return cursor < ChunkUtils.CHUNKS_PRE_REGION;
            }

            @Override
            public Chunk next() {
                if (cursor >= ChunkUtils.CHUNKS_PRE_REGION) {
                    throw new NoSuchElementException();
                }
                return getChunk(cursor++);
            }
        };
    }

    @Override
    public Stream<Chunk> stream() {
        for (int localIndex = 0; localIndex < ChunkUtils.CHUNKS_PRE_REGION; localIndex++) {
            if (chunks[localIndex] == null) {
                chunks[localIndex] = new Chunk(this, localIndex);
            }
        }
        //noinspection NullableProblems
        return Stream.of(chunks);
    }

    /// Remove the chunk at the given local index from this region.
    ///
    /// After removing the chunk, the original local index of the chunk will point to a new blank chunk.
    ///
    /// @throws IllegalArgumentException if the chunk is not in this region.
    @Override
    @Contract(mutates = "this,param1")
    public void removeElement(Chunk chunk) throws IllegalArgumentException {
        if (chunk.getParent() != this) {
            throw new IllegalArgumentException("The chunk is not in this region");
        }

        int localIndex = chunk.getLocalIndex();

        Chunk old = chunks[localIndex];
        if (old != chunk) {
            throw new AssertionError("Expected " + chunk + ", but got " + old);
        }
        chunk.setParent(null, -1);
        chunks[localIndex] = null;
    }

    @Override
    @Contract(value = "-> new", pure = true)
    public ChunkRegion clone() {
        var newRegion = new ChunkRegion();

        for (int i = 0; i < chunks.length; i++) {
            Chunk chunk = chunks[i];
            if (chunk != null) {
                newRegion.setChunk(i, chunk.clone());
            }
        }

        return newRegion;
    }

    @Override
    public int hashCode() {
        int hash = 0;
        for (Chunk chunk : this) {
            hash = 31 * hash + chunk.hashCode();
        }
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj instanceof ChunkRegion that) {
            for (int i = 0; i < chunks.length; i++) {
                if (!this.getChunk(i).equals(that.getChunk(i))) {
                    return false;
                }
            }
            return true;
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(65536);
        builder.append("Chunk[");

        for (int i = 0; i < chunks.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }

            builder.append(getChunk(i));
        }

        builder.append(']');

        return builder.toString();
    }
}
