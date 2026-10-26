// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.nbt;

import space.minecraftstl.xyml.library.nbt.NBTElement;
import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.edit.NBTAddress;
import space.minecraftstl.xyml.library.nbt.edit.NBTNode;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Stable toolkit-neutral node categories suitable for choosing text or image presentation in a UI.
@NotNullByDefault
public enum NBTNodeType {
    /// One byte scalar tag.
    BYTE,

    /// Two byte scalar tag.
    SHORT,

    /// Four byte scalar tag.
    INT,

    /// Eight byte scalar tag.
    LONG,

    /// Single-precision scalar tag.
    FLOAT,

    /// Double-precision scalar tag.
    DOUBLE,

    /// UTF string tag.
    STRING,

    /// Byte-array parent tag.
    BYTE_ARRAY,

    /// Integer-array parent tag.
    INT_ARRAY,

    /// Long-array parent tag.
    LONG_ARRAY,

    /// Homogeneous list parent tag.
    LIST,

    /// Named compound parent tag.
    COMPOUND,

    /// One chunk slot in a region.
    CHUNK,

    /// A 32 by 32 chunk region.
    CHUNK_REGION,

    /// A future XoyzNBT element type unknown to this launcher version.
    UNKNOWN;

    /// Maps a concrete XoyzNBT element to its stable presentation category.
    ///
    /// @param element source element
    /// @return corresponding node category
    static NBTNodeType fromRootElement(NBTElement element) {
        Objects.requireNonNull(element, "element");
        if (element instanceof ChunkRegion) {
            return CHUNK_REGION;
        }
        if (element instanceof Chunk) {
            return CHUNK;
        }
        if (!(element instanceof Tag tag)) {
            return UNKNOWN;
        }
        return fromTagType(tag.getType());
    }

    /// Maps immutable XoyzNBT node metadata to a stable presentation category.
    ///
    /// @param node immutable editor node
    /// @return corresponding node category
    static NBTNodeType fromNode(NBTNode node) {
        NBTNode selected = Objects.requireNonNull(node, "node");
        @Nullable TagType<?> type = selected.type();
        if (type != null) {
            return fromTagType(type);
        }
        List<NBTAddress.Segment> segments = selected.getAddress().segments();
        if (!segments.isEmpty()
                && segments.get(segments.size() - 1) instanceof NBTAddress.RegionChunkSegment) {
            return CHUNK;
        }
        return UNKNOWN;
    }

    /// Maps one XoyzNBT tag type to a stable presentation category.
    ///
    /// @param type tag type, or `null` for a non-tag root
    /// @return corresponding node category
    static NBTNodeType fromTagType(@Nullable TagType<?> type) {
        if (type == null) {
            return UNKNOWN;
        }
        if (type == TagType.BYTE) {
            return BYTE;
        }
        if (type == TagType.SHORT) {
            return SHORT;
        }
        if (type == TagType.INT) {
            return INT;
        }
        if (type == TagType.LONG) {
            return LONG;
        }
        if (type == TagType.FLOAT) {
            return FLOAT;
        }
        if (type == TagType.DOUBLE) {
            return DOUBLE;
        }
        if (type == TagType.STRING) {
            return STRING;
        }
        if (type == TagType.BYTE_ARRAY) {
            return BYTE_ARRAY;
        }
        if (type == TagType.INT_ARRAY) {
            return INT_ARRAY;
        }
        if (type == TagType.LONG_ARRAY) {
            return LONG_ARRAY;
        }
        if (type == TagType.LIST) {
            return LIST;
        }
        if (type == TagType.COMPOUND) {
            return COMPOUND;
        }
        return UNKNOWN;
    }
}
