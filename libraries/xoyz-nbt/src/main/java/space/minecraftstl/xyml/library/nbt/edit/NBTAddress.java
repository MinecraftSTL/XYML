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
// Added by MinecraftSTL in 2026 for the generic XoyzNBT editing API.
package space.minecraftstl.xyml.library.nbt.edit;

import space.minecraftstl.xyml.library.nbt.NBTElement;
import space.minecraftstl.xyml.library.nbt.NBTParent;
import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.tag.ArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.ParentTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Immutable address of an element in an NBT tag, chunk, or region tree.
///
/// Compound children are addressed by name, ordered list and primitive-array children by index,
/// and region/chunk boundaries use dedicated segments. Addresses never expose the mutable
/// elements they identify and can therefore be safely retained by callers.
@NotNullByDefault
public final class NBTAddress {
    /// One immutable path segment.
    public sealed interface Segment permits NameSegment, IndexSegment, RegionChunkSegment, ChunkRootSegment {
    }

    /// A named child of a compound tag.
    public record NameSegment(String name) implements Segment {
        /// Creates a named segment.
        public NameSegment {
            Objects.requireNonNull(name, "name");
        }
    }

    /// An indexed child of a list or primitive array.
    public record IndexSegment(int index) implements Segment {
        /// Creates an indexed segment.
        public IndexSegment {
            if (index < 0) {
                throw new IllegalArgumentException("index must be non-negative");
            }
        }
    }

    /// A fixed slot in a 32 by 32 chunk region.
    public record RegionChunkSegment(int localIndex) implements Segment {
        /// Creates a region-slot segment.
        public RegionChunkSegment {
            if (localIndex < 0 || localIndex >= 1024) {
                throw new IndexOutOfBoundsException("localIndex: " + localIndex);
            }
        }
    }

    /// The fixed root-tag slot inside a chunk.
    public record ChunkRootSegment() implements Segment {
        /// Creates the fixed chunk-root segment.
        public ChunkRootSegment {
        }
    }

    private static final NBTAddress ROOT = new NBTAddress(List.of());
    private final @Unmodifiable List<Segment> segments;

    private NBTAddress(List<? extends Segment> segments) {
        List<Segment> copy = new ArrayList<>(Objects.requireNonNull(segments, "segments"));
        for (Segment segment : copy) {
            Objects.requireNonNull(segment, "segment");
        }
        this.segments = Collections.unmodifiableList(copy);
    }

    /// Returns the empty root address.
    ///
    /// @return root address
    public static NBTAddress root() {
        return ROOT;
    }

    /// Creates an address from a sequence of segments.
    ///
    /// @param segments ordered path segments
    /// @return immutable address
    public static NBTAddress of(Iterable<? extends Segment> segments) {
        Objects.requireNonNull(segments, "segments");
        List<Segment> copy = new ArrayList<>();
        for (Segment segment : segments) {
            copy.add(Objects.requireNonNull(segment, "segment"));
        }
        return copy.isEmpty() ? ROOT : new NBTAddress(copy);
    }

    /// Derives an address by following parent links from an element to its root.
    ///
    /// @param element element in a well-formed tree
    /// @return immutable address
    /// @throws IllegalArgumentException when parent links are cyclic or unsupported
    public static NBTAddress from(NBTElement element) {
        Objects.requireNonNull(element, "element");
        List<Segment> reversed = new ArrayList<>();
        Set<NBTElement> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        NBTElement current = element;
        while (current.getParent() != null) {
            if (!seen.add(current)) {
                throw new IllegalArgumentException("Cannot address a cyclic NBT tree");
            }
            NBTParent<?> parent = Objects.requireNonNull(current.getParent(), "parent");
            if (parent instanceof ChunkRegion region && current instanceof Chunk chunk) {
                reversed.add(new RegionChunkSegment(chunk.getLocalIndex()));
            } else if (parent instanceof Chunk chunk && current instanceof CompoundTag) {
                reversed.add(new ChunkRootSegment());
            } else if (parent instanceof CompoundTag && current instanceof Tag tag) {
                reversed.add(new NameSegment(tag.getName()));
            } else if ((parent instanceof ParentTag<?> || parent instanceof ArrayTag<?, ?, ?, ?>)
                    && current instanceof Tag tag) {
                reversed.add(new IndexSegment(tag.getIndex()));
            } else {
                throw new IllegalArgumentException("Unsupported NBT parent relation: "
                        + parent.getClass().getName());
            }
            current = parent;
        }
        Collections.reverse(reversed);
        return reversed.isEmpty() ? ROOT : new NBTAddress(reversed);
    }

    /// Returns the immutable ordered segments.
    ///
    /// @return unmodifiable segment list
    public @Unmodifiable List<Segment> segments() {
        return segments;
    }

    /// Returns whether this is the root address.
    ///
    /// @return whether no segments are present
    public boolean isRoot() {
        return segments.isEmpty();
    }

    /// Appends a compound-name segment.
    ///
    /// @param name child name
    /// @return extended address
    public NBTAddress appendName(String name) {
        return append(new NameSegment(name));
    }

    /// Appends a list/array index segment.
    ///
    /// @param index child index
    /// @return extended address
    public NBTAddress appendIndex(int index) {
        return append(new IndexSegment(index));
    }

    /// Appends a region chunk-slot segment.
    ///
    /// @param localIndex region slot
    /// @return extended address
    public NBTAddress appendChunk(int localIndex) {
        return append(new RegionChunkSegment(localIndex));
    }

    /// Appends the fixed root slot of a chunk.
    ///
    /// @return extended address
    public NBTAddress appendChunkRoot() {
        return append(new ChunkRootSegment());
    }

    /// Appends the fixed root segment of a chunk.
    ///
    /// This is an alias for [#appendChunkRoot()] intended to make address-building code read
    /// naturally when the parent address already identifies a chunk slot.
    ///
    /// @return address with a chunk-root segment appended
    public NBTAddress appendRoot() {
        return appendChunkRoot();
    }

    /// Alias for [#appendName(String)].
    public NBTAddress child(String name) {
        return appendName(name);
    }

    /// Alias for [#appendIndex(int)].
    public NBTAddress child(int index) {
        return appendIndex(index);
    }

    /// Returns the address without its final segment, or root for a root address.
    ///
    /// @return parent address
    public NBTAddress parent() {
        return segments.isEmpty() ? ROOT : of(segments.subList(0, segments.size() - 1));
    }

    private NBTAddress append(Segment segment) {
        List<Segment> copy = new ArrayList<>(segments.size() + 1);
        copy.addAll(segments);
        copy.add(Objects.requireNonNull(segment, "segment"));
        return new NBTAddress(copy);
    }

    @Override
    public boolean equals(Object object) {
        return this == object || object instanceof NBTAddress other && segments.equals(other.segments);
    }

    @Override
    public int hashCode() {
        return segments.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder("$");
        for (Segment segment : segments) {
            if (segment instanceof NameSegment name) {
                result.append('.').append(name.name());
            } else if (segment instanceof IndexSegment index) {
                result.append('[').append(index.index()).append(']');
            } else if (segment instanceof RegionChunkSegment chunk) {
                result.append(".chunk[").append(chunk.localIndex()).append(']');
            } else {
                result.append(".root");
            }
        }
        return result.toString();
    }
}
