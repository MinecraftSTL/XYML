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
package space.minecraftstl.xyml.nbt;

import space.minecraftstl.xyml.library.nbt.NBTElement;
import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import org.jetbrains.annotations.NotNullByDefault;

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

    /// A future HelloNBT element type unknown to this launcher version.
    UNKNOWN;

    /// Maps a concrete HelloNBT element to its stable presentation category.
    ///
    /// @param element source element
    /// @return corresponding node category
    static NBTNodeType fromElement(NBTElement element) {
        if (element instanceof ChunkRegion) {
            return CHUNK_REGION;
        }
        if (element instanceof Chunk) {
            return CHUNK;
        }
        if (!(element instanceof Tag tag)) {
            return UNKNOWN;
        }
        TagType<?> type = tag.getType();
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
