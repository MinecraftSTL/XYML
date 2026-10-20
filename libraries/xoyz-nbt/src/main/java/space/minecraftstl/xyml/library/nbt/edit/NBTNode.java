/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
// Added by MinecraftSTL in 2026 for the generic XoyzNBT editing API.
package space.minecraftstl.xyml.library.nbt.edit;

import space.minecraftstl.xyml.library.nbt.tag.TagType;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Immutable metadata for a node in an [NBTEditor] session.
///
/// A node is a revision-bound handle rather than a mutable view of the underlying tree. The
/// editor uses its private session identity and revision to reject handles from another session
/// or from an earlier mutation. This class intentionally exposes no mutable NBT element.
@NotNullByDefault
public final class NBTNode {
    /// Opaque identity of the editor session which created this handle.
    private final Object session;

    /// Revision at which this handle was created.
    private final long revision;

    /// Immutable address of the represented element.
    private final NBTAddress address;

    /// Name captured from the represented element.
    private final String name;

    /// Tag type captured from the represented element, or `null` for chunk elements.
    private final @Nullable TagType<?> type;

    /// Number of immediate children captured from the represented element.
    private final int childCount;

    /// Scalar display value captured from the represented element, or `null` for containers.
    private final @Nullable String value;

    /// Creates immutable metadata for an editor-owned node.
    ///
    /// @param session private editor session identity
    /// @param revision editor revision in which this handle was created
    /// @param address immutable node address
    /// @param name node name, or an empty string for unnamed elements
    /// @param type tag type, or `null` for chunk and region elements
    /// @param childCount number of immediate children
    /// @param value scalar display value, or `null` for containers
    /// @throws IllegalArgumentException if `childCount` is negative
    NBTNode(Object session, long revision, NBTAddress address, String name,
            @Nullable TagType<?> type, int childCount, @Nullable String value) {
        if (childCount < 0) {
            throw new IllegalArgumentException("childCount must not be negative");
        }
        this.session = Objects.requireNonNull(session, "session");
        this.revision = revision;
        this.address = Objects.requireNonNull(address, "address");
        this.name = Objects.requireNonNull(name, "name");
        this.type = type;
        this.childCount = childCount;
        this.value = value;
    }

    /// Returns the immutable address represented by this node.
    ///
    /// @return node address
    @Contract(pure = true)
    public NBTAddress getAddress() {
        return address;
    }

    /// Returns the immutable address represented by this node.
    ///
    /// @return node address
    @Contract(pure = true)
    NBTAddress address() {
        return address;
    }

    /// Returns the node name, or an empty string when the element is unnamed.
    ///
    /// @return node name
    @Contract(pure = true)
    public String getName() {
        return name;
    }

    /// Returns the node name, or an empty string when the element is unnamed.
    ///
    /// @return node name
    @Contract(pure = true)
    public String name() {
        return name;
    }

    /// Returns the NBT tag type, or `null` for a chunk or chunk-region node.
    ///
    /// @return tag type, or `null` for non-tag elements
    @Contract(pure = true)
    public @Nullable TagType<?> getType() {
        return type;
    }

    /// Returns the NBT tag type, or `null` for a chunk or chunk-region node.
    ///
    /// @return tag type, or `null` for non-tag elements
    @Contract(pure = true)
    public @Nullable TagType<?> type() {
        return type;
    }

    /// Returns the number of immediate children represented by this node.
    ///
    /// @return child count
    @Contract(pure = true)
    public int getChildCount() {
        return childCount;
    }

    /// Returns the number of immediate children represented by this node.
    ///
    /// @return child count
    @Contract(pure = true)
    public int childCount() {
        return childCount;
    }

    /// Returns the scalar display value captured when this node was described.
    ///
    /// Container nodes and nodes without a scalar display value return `null`.
    ///
    /// @return scalar display value, or `null`
    @Contract(pure = true)
    public @Nullable String getValue() {
        return value;
    }

    /// Returns the scalar display value captured when this node was described.
    ///
    /// @return scalar display value, or `null`
    @Contract(pure = true)
    public @Nullable String value() {
        return value;
    }

    /// Returns the final numeric index represented by this node, or `-1` when it is named or root.
    ///
    /// Region chunk slots return their local index; a chunk root returns `0`.
    ///
    /// @return numeric node index, or `-1` when no numeric index applies
    @Contract(pure = true)
    public int getIndex() {
        if (address.segments().isEmpty()) {
            return -1;
        }
        NBTAddress.Segment segment = address.segments().get(address.segments().size() - 1);
        if (segment instanceof NBTAddress.IndexSegment index) {
            return index.index();
        }
        if (segment instanceof NBTAddress.RegionChunkSegment chunk) {
            return chunk.localIndex();
        }
        return segment instanceof NBTAddress.ChunkRootSegment ? 0 : -1;
    }

    /// Returns the editor revision in which this node was created.
    ///
    /// @return node revision
    @Contract(pure = true)
    public long getRevision() {
        return revision;
    }

    /// Returns whether this node is the root of its editor tree.
    ///
    /// @return `true` when the node address has no segments
    @Contract(pure = true)
    public boolean isRoot() {
        return address.isRoot();
    }

    /// Returns whether this node represents a container element.
    ///
    /// Chunk and region nodes have no [TagType], while compound, list, and primitive-array tags
    /// are recognized by their tag type even when they currently contain no children.
    ///
    /// @return `true` for a container element
    @Contract(pure = true)
    public boolean isContainer() {
        return type == null
                || type == TagType.COMPOUND
                || type == TagType.LIST
                || type == TagType.BYTE_ARRAY
                || type == TagType.INT_ARRAY
                || type == TagType.LONG_ARRAY;
    }

    /// Returns the private editor session identity used for stale-handle checks.
    ///
    /// @return opaque session identity
    @Contract(pure = true)
    Object session() {
        return session;
    }

    /// Returns the editor revision in which this node was created.
    ///
    /// @return node revision
    @Contract(pure = true)
    long revision() {
        return revision;
    }

    @Override
    public boolean equals(Object object) {
        return this == object
                || object instanceof NBTNode other
                && session == other.session
                && revision == other.revision
                && address.equals(other.address);
    }

    @Override
    public int hashCode() {
        int result = System.identityHashCode(session);
        result = 31 * result + Long.hashCode(revision);
        return 31 * result + address.hashCode();
    }

    /// Returns a concise diagnostic representation of this immutable node handle.
    @Override
    public String toString() {
        return "NBTNode[address=" + address + ", name=" + name + ", type=" + type
                + ", childCount=" + childCount + ", revision=" + revision + ']';
    }
}
