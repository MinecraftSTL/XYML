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
// Added by MinecraftSTL in 2026 for immutable NBT address coverage.
package space.minecraftstl.xyml.library.nbt.edit;

import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies immutable address segments for compound, list, chunk, and region paths.
@NotNullByDefault
public final class NBTAddressTest {
    /// Ensures parent links are translated to the corresponding stable segment kinds.
    @Test
    void derivesCompoundAndRegionAddresses() {
        CompoundTag root = new CompoundTag();
        CompoundTag child = new CompoundTag().setName("child");
        root.addTag(child);
        assertEquals(NBTAddress.root().appendName("child"), NBTAddress.from(child));

        ChunkRegion region = new ChunkRegion();
        Chunk chunk = region.getChunk(37);
        chunk.setRootTag(new CompoundTag().addInt("value", 1));
        assertEquals(NBTAddress.root().appendChunk(37), NBTAddress.from(chunk));
        assertEquals(NBTAddress.root().appendChunk(37).appendRoot(), NBTAddress.from(chunk.getRootTag()));
    }

    /// Ensures segment validation rejects negative and out-of-range indexes.
    @Test
    void validatesIndexes() {
        assertThrows(IllegalArgumentException.class, () -> new NBTAddress.IndexSegment(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> new NBTAddress.RegionChunkSegment(1024));
    }
}
