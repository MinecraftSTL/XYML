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
package space.minecraftstl.xyml.library.nbt.tag;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies atomic structural operations on mutable tag trees.
@NotNullByDefault
public final class MutableTreeInvariantTest {
    /// Ensures list cloning restores child parent and index metadata.
    @Test
    void listCloneRestoresOwnership() {
        ListTag<IntTag> source = new ListTag<>(TagType.INT);
        source.addTag(new IntTag(1));
        source.addTag(new IntTag(2));

        ListTag<IntTag> copy = source.clone();

        assertEquals(2, copy.size());
        assertSame(copy, copy.getTag(0).getParent());
        assertEquals(0, copy.getTag(0).getIndex());
        assertSame(copy, copy.getTag(1).getParent());
        assertEquals(1, copy.getTag(1).getIndex());
    }

    /// Ensures rejected cyclic insertion leaves the source tree unchanged.
    @Test
    void cyclicInsertionIsAtomic() {
        CompoundTag root = new CompoundTag();
        CompoundTag child = new CompoundTag();
        root.addTag("child", child);

        assertThrows(IllegalArgumentException.class, () -> child.addTag("root", root));

        assertSame(child, root.get("child"));
        assertSame(root, child.getParent());
        assertEquals(1, root.size());
        assertEquals(0, child.size());
    }

    /// Ensures exact insertion and movement preserve order and indexes.
    @Test
    void insertionAndMovementPreserveOrder() {
        ListTag<IntTag> list = new ListTag<>(TagType.INT);
        list.addTag(new IntTag(1));
        list.addTag(new IntTag(3));
        list.insertTag(1, new IntTag(2));

        assertEquals(1, list.getTag(0).get());
        assertEquals(2, list.getTag(1).get());
        assertEquals(3, list.getTag(2).get());

        list.moveTag(2, 0);
        assertEquals(3, list.getTag(0).get());
        assertEquals(0, list.getTag(0).getIndex());
        assertEquals(1, list.getTag(1).getIndex());
        assertEquals(2, list.getTag(2).getIndex());
    }

    /// Ensures array values and lazy child tags move together.
    @Test
    void arrayMoveKeepsPrimitiveValuesSynchronized() {
        IntArrayTag array = new IntArrayTag(new int[]{1, 2, 3});
        IntTag moved = array.getTag(2);

        array.moveTag(2, 0);

        assertEquals(3, array.get(0));
        assertEquals(1, array.get(1));
        assertEquals(2, array.get(2));
        assertSame(moved, array.getTag(0));
        assertEquals(0, moved.getIndex());
    }
}
