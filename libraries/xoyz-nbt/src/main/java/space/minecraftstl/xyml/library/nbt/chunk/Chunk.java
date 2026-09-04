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
// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt.chunk;

import space.minecraftstl.xyml.library.nbt.NBTElement;
import space.minecraftstl.xyml.library.nbt.NBTParent;
import space.minecraftstl.xyml.library.nbt.internal.Access;
import space.minecraftstl.xyml.library.nbt.internal.ChunkUtils;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.stream.Stream;

/// Represents a chunk in a region file.
///
/// A chunk can contain a root tag, which is usually a compound tag containing the chunk data.
@NotNullByDefault
public final class Chunk implements NBTParent<CompoundTag>, NBTElement {
    @Nullable ChunkRegion region;
    int localIndex = -1;

    @Nullable CompoundTag rootTag;
    Instant timestamp = Instant.EPOCH;

    /// Creates a new empty chunk.
    ///
    /// The root tag is initially `null`, and the timestamp is set to the epoch (1970-01-01T00:00:00Z).
    public Chunk() {
    }

    /// Creates a new empty chunk with the given timestamp.
    ///
    /// The root tag is initially `null`.
    public Chunk(Instant timestamp) {
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }

    /// Creates a new chunk with the given root tag.
    ///
    /// The timestamp is set to the epoch (1970-01-01T00:00:00Z).
    public Chunk(@Nullable CompoundTag rootTag) {
        setRootTag(rootTag);
    }

    /// Creates a new chunk with the given timestamp and root tag.
    public Chunk(Instant timestamp, @Nullable CompoundTag rootTag) {
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        setRootTag(rootTag);
    }

    Chunk(ChunkRegion region, int localIndex) {
        this.region = region;
        this.localIndex = localIndex;
    }

    /// Return the region of this chunk, or `null` if this chunk is not in any region.
    @Override
    @Contract(pure = true)
    public @Nullable ChunkRegion getParent() {
        return region;
    }

    void setParent(@Nullable ChunkRegion region, int localIndex) {
        this.region = region;
        this.localIndex = localIndex;
    }

    /// Return the local index of this chunk in its region, or -1 if this chunk is not in any region.
    @Contract(pure = true)
    public int getLocalIndex() {
        return localIndex;
    }

    /// Return the local x coordinate of this chunk in its region, or -1 if this chunk is not in any region.
    @Contract(pure = true)
    public int getLocalX() {
        return localIndex >= 0 ? ChunkUtils.getLocalX(localIndex) : -1;
    }

    /// Return the local z coordinate of this chunk in its region, or -1 if this chunk is not in any region.
    @Contract(pure = true)
    public int getLocalZ() {
        return localIndex >= 0 ? ChunkUtils.getLocalZ(localIndex) : -1;
    }

    /// Returns `true` if this chunk has no root tag, `false` otherwise.
    public boolean isEmpty() {
        return rootTag == null;
    }

    /// Returns `1` if this chunk has a root tag, `0` otherwise.
    @Override
    public int size() {
        return rootTag != null ? 1 : 0;
    }

    @Override
    public Iterator<CompoundTag> iterator() {
        //noinspection NullableProblems
        return rootTag != null ? Collections.singleton(rootTag).iterator() : Collections.emptyIterator();
    }

    /// Returns the root tag of this chunk, or `null` if this chunk has no root tag.
    @Contract(pure = true)
    public @Nullable CompoundTag getRootTag() {
        return rootTag;
    }

    /// Sets the root tag of this chunk.
    @Contract(value = "_ -> this", mutates = "this,param1")
    public Chunk setRootTag(@Nullable CompoundTag rootTag) {
        if (rootTag == this.rootTag) {
            return this;
        }

        validateCurrentRoot();
        @Nullable NBTParent<? extends Tag> oldParent = rootTag == null ? null : rootTag.getParent();
        if (rootTag != null) {
            validateCandidateRoot(rootTag, oldParent);
        }

        if (oldParent != null) {
            // The candidate is already owned elsewhere, so detach it only after every ownership
            // check has completed. A validated parent/index pair makes this call non-failing.
            @SuppressWarnings("unchecked")
            NBTParent<Tag> typedParent = (NBTParent<Tag>) oldParent;
            typedParent.removeElement(rootTag);
        }
        if (this.rootTag != null) {
            Access.TAG.setParent(this.rootTag, null, -1);
        }
        if (rootTag != null) {
            Access.TAG.setParent(rootTag, this, 0);
        }
        this.rootTag = rootTag;
        return this;
    }

    /// Validates the current root metadata before any replacement can detach it.
    private void validateCurrentRoot() {
        if (rootTag != null && (rootTag.getParent() != this || rootTag.getIndex() != 0)) {
            throw new IllegalStateException("The chunk root ownership invariant is inconsistent");
        }
    }

    /// Validates a candidate root's existing parent and index without changing either tree.
    ///
    /// @param candidate candidate root
    /// @param oldParent candidate's current parent
    private void validateCandidateRoot(
            CompoundTag candidate,
            @Nullable NBTParent<? extends Tag> oldParent) {
        if (oldParent == this) {
            throw new IllegalArgumentException("The candidate is already owned by this chunk");
        }
        if (oldParent == null) {
            if (candidate.getIndex() != -1) {
                throw new IllegalArgumentException("The detached root has an invalid index");
            }
            return;
        }
        if (oldParent instanceof Chunk oldChunk) {
            if (candidate.getIndex() != 0 || oldChunk.getRootTag() != candidate) {
                throw new IllegalArgumentException("The candidate root has an invalid chunk index");
            }
            return;
        }
        if (oldParent instanceof space.minecraftstl.xyml.library.nbt.tag.ParentTag<?> parentTag) {
            int index = candidate.getIndex();
            if (index < 0 || index >= parentTag.size() || parentTag.getTag(index) != candidate) {
                throw new IllegalArgumentException("The candidate root has an invalid parent index");
            }
            return;
        }
        throw new IllegalArgumentException("Unsupported candidate root parent: " + oldParent.getClass());
    }

    /// Return the timestamp of this chunk.
    ///
    /// The default value is the epoch (1970-01-01T00:00:00Z).
    @Contract(pure = true)
    public Instant getTimestamp() {
        return timestamp;
    }

    /// Set the timestamp of this chunk.
    ///
    /// @apiNote The timestamp can be set to any instant, but when written to a file,
    /// it will only retain precision up to seconds.
    /// Timestamps exceeding the range of unsigned 32-bit epoch seconds will be truncated to `2106-02-07T06:28:15Z`.
    @Contract(value = "_ -> this", mutates = "this")
    public Chunk setTimestamp(Instant timestamp) {
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        return this;
    }

    /// Removes this chunk's root tag and detaches it from the chunk.
    ///
    /// @throws IllegalArgumentException if the supplied tag is not this chunk's root tag
    @Override
    @Contract(mutates = "this,param1")
    public void removeElement(CompoundTag element) throws IllegalArgumentException {
        if (element.getParent() != this) {
            throw new IllegalArgumentException("The tag is not the root tag of this chunk");
        }

        if (element != rootTag) {
            throw new AssertionError("Expected " + element + ", but got " + rootTag);
        }
        if (element.getIndex() != 0) {
            throw new AssertionError("Expected index 0, but got " + element.getIndex());
        }

        Access.TAG.setParent(element, null, -1);
        rootTag = null;
    }

    @Override
    public Stream<CompoundTag> stream() {
        //noinspection NullableProblems
        return Stream.ofNullable(rootTag);
    }

    @Override
    @Contract(value = "-> new", pure = true)
    public Chunk clone() {
        Chunk newChunk = new Chunk();
        newChunk.setTimestamp(getTimestamp());
        newChunk.setRootTag(getRootTag() != null ? getRootTag().clone() : null);
        return newChunk;
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestamp, rootTag);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || obj instanceof Chunk that
                && this.timestamp.equals(that.timestamp)
                && Objects.equals(this.rootTag, that.rootTag);
    }

    @Override
    public String toString() {
        return "Chunk[x=" + getLocalX() + ", z=" + getLocalZ() + ", root=" + rootTag + ']';
    }
}
