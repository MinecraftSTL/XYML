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
// Added by MinecraftSTL in 2026 for XoyzNBT batch ownership regression coverage.
package space.minecraftstl.xyml.library.nbt.tag;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that batched parent-tag changes are preflighted and committed atomically.
@NotNullByDefault
public final class ParentTagBatchAtomicityTest {
    /// Ensures a later list type failure does not detach an earlier candidate.
    @Test
    void listBatchFailureLeavesBothTreesUntouched() {
        ListTag<Tag> destination = new ListTag<>();
        destination.setElementType(TagType.INT);
        ListTag<IntTag> source = new ListTag<>(TagType.INT);
        IntTag movable = new IntTag(1);
        source.addTag(movable);
        StringTag invalid = new StringTag("invalid");

        assertThrows(IllegalArgumentException.class,
                () -> destination.addTags(movable, invalid));

        assertTrue(destination.isEmpty());
        assertEquals(1, source.size());
        assertSame(movable, source.getTag(0));
        assertSame(source, movable.getParent());
        assertEquals(0, movable.getIndex());
        assertNull(invalid.getParent());
        assertEquals(-1, invalid.getIndex());
    }

    /// Ensures a cyclic candidate is rejected before another candidate is moved from its source.
    @Test
    void cycleFailureLeavesCrossParentBatchUntouched() {
        CompoundTag destination = new CompoundTag();
        CompoundTag source = new CompoundTag();
        IntTag movable = new IntTag(1).setName("movable");
        source.addTag(movable);

        assertThrows(IllegalArgumentException.class,
                () -> destination.addTags(List.of(movable, destination)));

        assertTrue(destination.isEmpty());
        assertSame(movable, source.get("movable"));
        assertSame(source, movable.getParent());
        assertEquals(0, movable.getIndex());
    }

    /// Ensures moving all children from an iterable source does not skip shifted successors.
    @Test
    void movingIterableSourceTransfersEveryChild() {
        CompoundTag source = new CompoundTag();
        IntTag first = new IntTag(1).setName("first");
        IntTag second = new IntTag(2).setName("second");
        IntTag third = new IntTag(3).setName("third");
        source.addTags(first, second, third);

        CompoundTag destination = new CompoundTag();
        destination.addTags(source);

        assertTrue(source.isEmpty());
        assertEquals(3, destination.size());
        assertSame(first, destination.getTag(0));
        assertSame(second, destination.getTag(1));
        assertSame(third, destination.getTag(2));
        assertSame(destination, first.getParent());
        assertSame(destination, second.getParent());
        assertSame(destination, third.getParent());
        assertEquals(0, first.getIndex());
        assertEquals(1, second.getIndex());
        assertEquals(2, third.getIndex());
    }

    /// Ensures attached candidates can be moved as a batch without losing order or ownership.
    @Test
    void movingAttachedSubsetPreservesAllChildren() {
        CompoundTag parent = new CompoundTag();
        IntTag first = new IntTag(1).setName("first");
        IntTag second = new IntTag(2).setName("second");
        IntTag third = new IntTag(3).setName("third");
        parent.addTags(first, second, third);

        parent.addTags(List.of(first, second));

        assertEquals(3, parent.size());
        assertSame(third, parent.getTag(0));
        assertSame(first, parent.getTag(1));
        assertSame(second, parent.getTag(2));
        assertEquals(0, third.getIndex());
        assertEquals(1, first.getIndex());
        assertEquals(2, second.getIndex());
    }

    /// Ensures duplicate object identities fail before the first move is committed.
    @Test
    void duplicateIdentityFailureLeavesSourceUntouched() {
        CompoundTag source = new CompoundTag();
        IntTag child = new IntTag(1).setName("child");
        source.addTag(child);
        CompoundTag destination = new CompoundTag();

        assertThrows(IllegalArgumentException.class,
                () -> destination.addTags(child, child));

        assertTrue(destination.isEmpty());
        assertEquals(1, source.size());
        assertSame(child, source.get("child"));
        assertSame(source, child.getParent());
        assertEquals(0, child.getIndex());
    }
}
