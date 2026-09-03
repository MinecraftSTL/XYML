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
// Added by MinecraftSTL in 2026 for XoyzNBT mutable tree regression coverage.
package space.minecraftstl.xyml.library.nbt.chunk;

import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that chunks maintain root tag ownership and index invariants.
@NotNullByDefault
public final class ChunkTest {
    /// Verifies removing the owned root clears both sides of the parent relationship.
    @Test
    void removeElementDetachesOwnedRoot() {
        CompoundTag root = new CompoundTag();
        Chunk chunk = new Chunk(root);

        chunk.removeElement(root);

        assertTrue(chunk.isEmpty());
        assertNull(chunk.getRootTag());
        assertNull(root.getParent());
        assertEquals(-1, root.getIndex());
    }

    /// Verifies removing a foreign tag fails without changing either chunk.
    @Test
    void removeElementRejectsForeignRootWithoutMutation() {
        CompoundTag ownedRoot = new CompoundTag();
        CompoundTag foreignRoot = new CompoundTag();
        Chunk chunk = new Chunk(ownedRoot);
        Chunk foreignChunk = new Chunk(foreignRoot);

        assertThrows(IllegalArgumentException.class, () -> chunk.removeElement(foreignRoot));

        assertSame(ownedRoot, chunk.getRootTag());
        assertSame(chunk, ownedRoot.getParent());
        assertEquals(0, ownedRoot.getIndex());
        assertSame(foreignRoot, foreignChunk.getRootTag());
        assertSame(foreignChunk, foreignRoot.getParent());
        assertEquals(0, foreignRoot.getIndex());
    }

    /// Verifies replacing a root detaches the previous root and attaches the replacement at index zero.
    @Test
    void setRootTagReplacesOwnership() {
        CompoundTag previousRoot = new CompoundTag();
        CompoundTag replacement = new CompoundTag();
        Chunk chunk = new Chunk(previousRoot);

        chunk.setRootTag(replacement);

        assertNull(previousRoot.getParent());
        assertEquals(-1, previousRoot.getIndex());
        assertSame(replacement, chunk.getRootTag());
        assertSame(chunk, replacement.getParent());
        assertEquals(0, replacement.getIndex());
    }

    /// Verifies assigning a root owned by another chunk transfers ownership without duplication.
    @Test
    void setRootTagTransfersOwnershipBetweenChunks() {
        CompoundTag movedRoot = new CompoundTag();
        CompoundTag displacedRoot = new CompoundTag();
        Chunk source = new Chunk(movedRoot);
        Chunk destination = new Chunk(displacedRoot);

        destination.setRootTag(movedRoot);

        assertTrue(source.isEmpty());
        assertNull(source.getRootTag());
        assertNull(displacedRoot.getParent());
        assertEquals(-1, displacedRoot.getIndex());
        assertSame(movedRoot, destination.getRootTag());
        assertSame(destination, movedRoot.getParent());
        assertEquals(0, movedRoot.getIndex());
    }
}
