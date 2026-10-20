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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /// Ensures named insertion validates every condition before renaming a detached candidate.
    @Test
    void namedInsertionRejectsAtomically() {
        CompoundTag root = new CompoundTag().addInt("taken", 1);
        Tag existing = root.get("taken");
        IntTag candidate = new IntTag(2).setName("before");

        assertThrows(IndexOutOfBoundsException.class, () -> root.insertTag(2, "after", candidate));
        assertEquals("before", candidate.getName());
        assertNull(candidate.getParent());
        assertEquals(-1, candidate.getIndex());
        assertEquals(1, root.size());
        assertSame(existing, root.get("taken"));

        assertThrows(IllegalArgumentException.class, () -> root.insertTag(0, "", candidate));
        assertEquals("before", candidate.getName());
        assertNull(candidate.getParent());
        assertEquals(-1, candidate.getIndex());
        assertEquals(1, root.size());
        assertSame(existing, root.get("taken"));

        assertThrows(NullPointerException.class, () -> root.insertTag(0, null, candidate));
        assertEquals("before", candidate.getName());
        assertNull(candidate.getParent());
        assertEquals(-1, candidate.getIndex());
        assertEquals(1, root.size());
        assertSame(existing, root.get("taken"));

        assertThrows(IllegalArgumentException.class, () -> root.insertTag(0, "taken", candidate));
        assertEquals("before", candidate.getName());
        assertNull(candidate.getParent());
        assertEquals(-1, candidate.getIndex());
        assertEquals(1, root.size());
        assertSame(existing, root.get("taken"));
    }

    /// Ensures named insertion rejects inconsistent detached metadata before changing either side.
    @Test
    void namedInsertionRejectsInvalidDetachedIndexAtomically() {
        CompoundTag root = new CompoundTag().addInt("existing", 1);
        Tag existing = root.get("existing");
        IntTag candidate = new IntTag(2).setName("before");
        candidate.setIndex(7);

        assertThrows(IllegalArgumentException.class, () -> root.insertTag(0, "after", candidate));

        assertEquals("before", candidate.getName());
        assertNull(candidate.getParent());
        assertEquals(7, candidate.getIndex());
        assertEquals(1, root.size());
        assertSame(existing, root.get("existing"));
    }

    /// Ensures every direct container mutator rejects inconsistent detached metadata without mutation.
    @Test
    void directContainerMutatorsRejectInvalidDetachedIndexesAtomically() {
        CompoundTag compound = new CompoundTag().addInt("existing", 1);
        IntTag compoundInsert = invalidDetachedInt(2, "insert", 4);
        IntTag compoundReplace = invalidDetachedInt(3, "replacement", 5);
        IntTag compoundAdd = invalidDetachedInt(4, "addition", 6);

        assertThrows(IllegalArgumentException.class, () -> compound.insertTag(1, compoundInsert));
        assertThrows(IllegalArgumentException.class, () -> compound.replaceTagAt(0, compoundReplace));
        assertThrows(IllegalArgumentException.class, () -> compound.addTag(compoundAdd));
        assertEquals(1, compound.size());
        assertEquals(1, compound.getInt("existing"));
        assertInvalidDetached(compoundInsert, "insert", 4);
        assertInvalidDetached(compoundReplace, "replacement", 5);
        assertInvalidDetached(compoundAdd, "addition", 6);

        ListTag<IntTag> list = new ListTag<>(TagType.INT);
        list.addTag(new IntTag(1));
        IntTag listInsert = invalidDetachedInt(2, "", 4);
        IntTag listReplace = invalidDetachedInt(3, "", 5);
        IntTag listAdd = invalidDetachedInt(4, "", 6);

        assertThrows(IllegalArgumentException.class, () -> list.insertTag(1, listInsert));
        assertThrows(IllegalArgumentException.class, () -> list.replaceTagAt(0, listReplace));
        assertThrows(IllegalArgumentException.class, () -> list.addTag(listAdd));
        assertEquals(1, list.size());
        assertEquals(1, list.getTag(0).get());
        assertInvalidDetached(listInsert, "", 4);
        assertInvalidDetached(listReplace, "", 5);
        assertInvalidDetached(listAdd, "", 6);

        IntArrayTag array = new IntArrayTag(new int[]{1});
        IntTag arrayInsert = invalidDetachedInt(2, "", 4);
        IntTag arrayReplace = invalidDetachedInt(3, "", 5);
        IntTag arrayAdd = invalidDetachedInt(4, "", 6);

        assertThrows(IllegalArgumentException.class, () -> array.insertTag(1, arrayInsert));
        assertThrows(IllegalArgumentException.class, () -> array.replaceTagAt(0, arrayReplace));
        assertThrows(IllegalArgumentException.class, () -> array.addTag(arrayAdd));
        assertEquals(1, array.size());
        assertEquals(1, array.get(0));
        assertInvalidDetached(arrayInsert, "", 4);
        assertInvalidDetached(arrayReplace, "", 5);
        assertInvalidDetached(arrayAdd, "", 6);
    }

    /// Ensures named insertion rejects an attached candidate without detaching or renaming it.
    @Test
    void namedInsertionRejectsAttachedCandidateAtomically() {
        CompoundTag source = new CompoundTag();
        IntTag candidate = new IntTag(2).setName("before");
        source.addTag(candidate);
        CompoundTag destination = new CompoundTag().addInt("existing", 1);
        Tag existing = destination.get("existing");

        assertThrows(IllegalArgumentException.class,
                () -> destination.insertTag(0, "after", candidate));

        assertEquals("before", candidate.getName());
        assertSame(source, candidate.getParent());
        assertEquals(0, candidate.getIndex());
        assertEquals(1, source.size());
        assertSame(candidate, source.get("before"));
        assertEquals(1, destination.size());
        assertSame(existing, destination.get("existing"));
    }

    /// Ensures named insertion detects an ancestor cycle before renaming the ancestor candidate.
    @Test
    void namedInsertionRejectsCycleAtomically() {
        CompoundTag ancestor = new CompoundTag().setName("before");
        CompoundTag destination = new CompoundTag().setName("destination");
        ancestor.addTag(destination);

        assertThrows(IllegalArgumentException.class,
                () -> destination.insertTag(0, "after", ancestor));

        assertEquals("before", ancestor.getName());
        assertNull(ancestor.getParent());
        assertEquals(-1, ancestor.getIndex());
        assertEquals(1, ancestor.size());
        assertSame(destination, ancestor.get("destination"));
        assertSame(ancestor, destination.getParent());
        assertEquals(0, destination.getIndex());
        assertEquals(0, destination.size());
    }

    /// Ensures array values and lazy child tags move together.
    @Test
    void arrayMoveKeepsPrimitiveValuesSynchronized() {
        IntArrayTag array = new IntArrayTag(new int[]{1, 2, 3});

        array.moveTag(2, 0);

        assertEquals(3, array.get(0));
        assertEquals(1, array.get(1));
        assertEquals(2, array.get(2));
        assertEquals(3, array.getTag(0).get());
        assertEquals(0, array.getTag(0).getIndex());
    }

    /// Ensures a completely lazy primitive array can remove an element without corrupting values.
    @Test
    void lazyArrayRemovalKeepsPrimitiveValuesSynchronized() {
        IntArrayTag array = new IntArrayTag(new int[]{1, 2, 3});

        IntTag removed = array.removeTagAt(1);

        assertEquals(2, removed.get());
        assertNull(removed.getParent());
        assertEquals(-1, removed.getIndex());
        assertArrayEquals(new int[]{1, 3}, array.getArray());

        IntArrayTag withoutReturn = new IntArrayTag(new int[]{4, 5});
        withoutReturn.removeAt(0);
        assertArrayEquals(new int[]{5}, withoutReturn.getArray());
    }

    /// Ensures invalid replacement-array input is rejected before existing children are detached.
    @Test
    void arraySetAllRejectsBeforeClearingExistingValues() {
        IntArrayTag array = new IntArrayTag(new int[]{1, 2});
        IntTag first = array.getTag(0);

        assertThrows(NullPointerException.class, () -> array.setAll((int[]) null));

        assertArrayEquals(new int[]{1, 2}, array.getArray());
        assertSame(first, array.getTag(0));
        assertSame(array, first.getParent());
        assertEquals(0, first.getIndex());
    }

    /// Ensures heterogeneous List adaptation validates the candidate before changing its type or children.
    @Test
    void heterogeneousListAdditionRejectsAtomically() {
        var typedEmpty = new ListTag<>();
        typedEmpty.setElementType(TagType.INT);
        StringTag invalidEmptyCandidate = new StringTag("value");
        invalidEmptyCandidate.setIndex(4);

        assertThrows(IllegalArgumentException.class, () -> typedEmpty.addAnyTag(invalidEmptyCandidate));
        assertSame(TagType.INT, typedEmpty.getElementType());
        assertEquals(0, typedEmpty.size());
        assertInvalidDetached(invalidEmptyCandidate, "", 4);

        var populated = new ListTag<>();
        populated.setElementType(TagType.INT);
        IntTag existing = new IntTag(1);
        populated.addTag(existing);
        StringTag invalidPopulatedCandidate = new StringTag("value");
        invalidPopulatedCandidate.setIndex(5);

        assertThrows(IllegalArgumentException.class, () -> populated.addAnyTag(invalidPopulatedCandidate));
        assertSame(TagType.INT, populated.getElementType());
        assertEquals(1, populated.size());
        assertSame(existing, populated.getTag(0));
        assertSame(populated, existing.getParent());
        assertEquals(0, existing.getIndex());
        assertInvalidDetached(invalidPopulatedCandidate, "", 5);

        ListTag<StringTag> source = new ListTag<>(TagType.STRING);
        StringTag transferable = new StringTag("first");
        StringTag corruptSuccessor = new StringTag("second");
        source.addTag(transferable).addTag(corruptSuccessor);
        corruptSuccessor.setIndex(7);
        var destination = new ListTag<>();
        destination.setElementType(TagType.INT);

        assertThrows(IllegalArgumentException.class, () -> destination.addAnyTag(transferable));
        assertSame(TagType.INT, destination.getElementType());
        assertEquals(0, destination.size());
        assertSame(transferable, source.getTag(0));
        assertSame(source, transferable.getParent());
        assertEquals(0, transferable.getIndex());
        assertSame(corruptSuccessor, source.getTag(1));
        assertEquals(7, corruptSuccessor.getIndex());
    }

    /// Ensures a rejected replacement array leaves its current values and cached children intact.
    @Test
    void arraySetAllRejectsBeforeClearingCurrentValues() {
        IntArrayTag array = new IntArrayTag(new int[]{1, 2});
        IntTag cached = array.getTag(0);

        assertThrows(NullPointerException.class, () -> array.setAll((int[]) null));

        assertArrayEquals(new int[]{1, 2}, array.getArray());
        assertSame(cached, array.getTag(0));
        assertSame(array, cached.getParent());
        assertEquals(0, cached.getIndex());
    }

    /// Ensures no-op and ordered moves reject corrupt child metadata before changing content.
    @Test
    void movementValidatesEveryAffectedChild() {
        ListTag<IntTag> noOp = new ListTag<>(TagType.INT);
        IntTag corruptOnlyChild = new IntTag(1);
        noOp.addTag(corruptOnlyChild);
        corruptOnlyChild.setIndex(7);

        assertThrows(IllegalArgumentException.class, () -> noOp.moveTag(0, 0));
        assertSame(corruptOnlyChild, noOp.getTag(0));
        assertEquals(7, corruptOnlyChild.getIndex());

        ListTag<IntTag> reordered = new ListTag<>(TagType.INT);
        IntTag first = new IntTag(1);
        IntTag corruptSecond = new IntTag(2);
        reordered.addTag(first).addTag(corruptSecond);
        corruptSecond.setIndex(8);

        assertThrows(IllegalArgumentException.class, () -> reordered.addTag(first));
        assertSame(first, reordered.getTag(0));
        assertSame(corruptSecond, reordered.getTag(1));
        assertEquals(0, first.getIndex());
        assertEquals(8, corruptSecond.getIndex());

        IntArrayTag array = new IntArrayTag(new int[]{1, 2});
        IntTag corruptArrayChild = array.getTag(1);
        corruptArrayChild.setIndex(9);

        assertThrows(IllegalArgumentException.class, () -> array.moveTag(0, 1));
        assertArrayEquals(new int[]{1, 2}, array.getArray());
        assertSame(corruptArrayChild, array.getTag(1));
        assertEquals(9, corruptArrayChild.getIndex());
    }

    /// Ensures insertion and replacement validate destination slots before attaching candidates.
    @Test
    void insertionAndReplacementRejectCorruptDestinationsAtomically() {
        ListTag<IntTag> insertionTarget = new ListTag<>(TagType.INT);
        IntTag first = new IntTag(1);
        IntTag corruptSecond = new IntTag(2);
        insertionTarget.addTag(first).addTag(corruptSecond);
        corruptSecond.setIndex(7);
        IntTag insertion = new IntTag(3);

        assertThrows(IllegalArgumentException.class, () -> insertionTarget.insertTag(1, insertion));
        assertEquals(2, insertionTarget.size());
        assertSame(first, insertionTarget.getTag(0));
        assertSame(corruptSecond, insertionTarget.getTag(1));
        assertInvalidDetached(insertion, "", -1);

        ListTag<IntTag> replacementTarget = new ListTag<>(TagType.INT);
        IntTag corruptExisting = new IntTag(4);
        replacementTarget.addTag(corruptExisting);
        corruptExisting.setIndex(8);
        IntTag replacement = new IntTag(5);

        assertThrows(IllegalArgumentException.class,
                () -> replacementTarget.replaceTagAt(0, replacement));
        assertEquals(1, replacementTarget.size());
        assertSame(corruptExisting, replacementTarget.getTag(0));
        assertEquals(8, corruptExisting.getIndex());
        assertInvalidDetached(replacement, "", -1);
    }

    /// Ensures fluent Compound replacement validates its destination before detaching its source.
    @Test
    void compoundAdditionRejectsCorruptDestinationAtomically() {
        CompoundTag source = new CompoundTag();
        IntTag candidate = new IntTag(2).setName("value");
        source.addTag(candidate);
        CompoundTag destination = new CompoundTag();
        IntTag corruptExisting = new IntTag(1).setName("value");
        destination.addTag(corruptExisting);
        corruptExisting.setIndex(7);

        assertThrows(IllegalArgumentException.class, () -> destination.addTag(candidate));

        assertSame(candidate, source.get("value"));
        assertSame(source, candidate.getParent());
        assertEquals(0, candidate.getIndex());
        assertSame(corruptExisting, destination.get("value"));
        assertEquals(7, corruptExisting.getIndex());
    }

    /// Ensures an unsupported empty-name replacement does not rename its detached candidate.
    @Test
    void namedReplacementRejectsBeforeRenamingCandidate() {
        CompoundTag compound = new CompoundTag();
        IntTag existing = new IntTag(1).setName("");
        compound.addTag(existing);
        IntTag candidate = new IntTag(2).setName("before");

        assertThrows(IllegalArgumentException.class, () -> compound.replaceTag("", candidate));

        assertSame(existing, compound.get(""));
        assertEquals("before", candidate.getName());
        assertInvalidDetached(candidate, "before", -1);
    }

    /// Ensures heterogeneous adaptation detects an ancestor cycle before changing the List.
    @Test
    void heterogeneousListAdditionRejectsAncestorCycleAtomically() {
        CompoundTag ancestor = new CompoundTag();
        var list = new ListTag<Tag>();
        ancestor.addTag("list", list);

        assertThrows(IllegalArgumentException.class, () -> list.addAnyTag(ancestor));

        assertNull(list.getElementType());
        assertEquals(0, list.size());
        assertSame(list, ancestor.get("list"));
        assertNull(ancestor.getParent());
        assertEquals(-1, ancestor.getIndex());
    }

    /// Creates an intentionally inconsistent detached tag for package-level invariant tests.
    ///
    /// @param value tag value
    /// @param name tag name
    /// @param index invalid detached index
    /// @return inconsistent tag
    private static IntTag invalidDetachedInt(int value, String name, int index) {
        IntTag tag = new IntTag(value).setName(name);
        tag.setIndex(index);
        return tag;
    }

    /// Verifies that a rejected candidate retained all of its pre-call metadata.
    ///
    /// @param tag rejected candidate
    /// @param name expected name
    /// @param index expected invalid index
    private static void assertInvalidDetached(Tag tag, String name, int index) {
        assertEquals(name, tag.getName());
        assertNull(tag.getParent());
        assertEquals(index, tag.getIndex());
    }
}
