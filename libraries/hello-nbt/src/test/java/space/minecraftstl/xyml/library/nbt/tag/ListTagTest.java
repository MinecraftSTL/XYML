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
package space.minecraftstl.xyml.library.nbt.tag;

import space.minecraftstl.xyml.library.nbt.internal.ArrayAccessor;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

final class ListTagTest {

    private static void assertDetached(Tag tag) {
        assertNull(tag.getParent());
        assertNull(tag.getParentTag());
        assertEquals(-1, tag.getIndex());
    }

    private static void assertAttached(ListTag<?> parent, Tag tag, int index) {
        assertSame(parent, tag.getParent());
        assertSame(parent, tag.getParentTag());
        assertEquals(index, tag.getIndex());
        assertSame(tag, parent.getTag(index));
        assertEquals("", tag.getName());
    }

    private static void assertContentEquals(ListTag<?> expected, ListTag<?> actual) {
        Supplier<String> errorMessage = () -> "Expected %s to be content-equal to %s".formatted(expected, actual);

        assertTrue(expected.contentEquals(actual), errorMessage);
        assertTrue(actual.contentEquals(expected), errorMessage);
        assertEquals(expected.contentHashCode(), actual.contentHashCode(), errorMessage);
    }

    private static void assertContentNotEquals(ListTag<?> expected, ListTag<?> actual) {
        Supplier<String> errorMessage = () -> "Expected %s not to be content-equal to %s".formatted(expected, actual);

        assertFalse(expected.contentEquals(actual), errorMessage);
        assertFalse(actual.contentEquals(expected), errorMessage);
        assertNotEquals(expected.contentHashCode(), actual.contentHashCode(), errorMessage);
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void testConstructorAndGetType() {
        ListTag<?> tag = new ListTag<>();
        assertEquals("", tag.getName());
        assertSame(TagType.LIST, tag.getType());
        assertTrue(tag.isEmpty());
        assertEquals(0, tag.size());
        assertNull(tag.getElementType());
        assertDetached(tag);

        tag = new ListTag<>(TagType.INT);
        assertEquals("", tag.getName());
        assertSame(TagType.LIST, tag.getType());
        assertTrue(tag.isEmpty());
        assertEquals(0, tag.size());
        assertSame(TagType.INT, tag.getElementType());
        assertDetached(tag);
    }

    @Test
    void testAddTagMoveRemoveAndClear() {
        var root = new ListTag<>().setName("root");

        var first = new IntTag(1).setName("first");
        var second = new IntTag(2).setName("second");
        root.addTag(first);
        root.addTag(second);

        assertEquals(2, root.size());
        assertSame(TagType.INT, root.getElementType());
        assertAttached(root, first, 0);
        assertAttached(root, second, 1);
        assertThrows(IllegalArgumentException.class, () -> first.setName("renamed"));

        root.addTag(first);
        assertSame(second, root.getTag(0));
        assertSame(first, root.getTag(1));
        assertAttached(root, second, 0);
        assertAttached(root, first, 1);

        var other = new ListTag<>(TagType.INT);
        var moved = new IntTag(9).setName("old");
        other.addTag(moved);
        root.addTag(moved);

        assertTrue(other.isEmpty());
        assertSame(TagType.INT, other.getElementType());
        assertEquals(3, root.size());
        assertSame(second, root.getTag(0));
        assertSame(first, root.getTag(1));
        assertSame(moved, root.getTag(2));
        assertAttached(root, moved, 2);

        Tag removed = root.removeTagAt(1);
        assertSame(first, removed);
        assertDetached(removed);
        assertEquals(2, root.size());
        assertAttached(root, second, 0);
        assertAttached(root, moved, 1);

        root.removeTag(second);
        assertDetached(second);
        assertEquals(1, root.size());
        assertAttached(root, moved, 0);
        assertThrows(IllegalArgumentException.class, () -> root.removeTag(second));

        root.clear();
        assertTrue(root.isEmpty());
        assertEquals(0, root.size());
        assertSame(TagType.INT, root.getElementType());
        assertDetached(moved);
        assertThrows(IllegalArgumentException.class, () -> root.addTag(new StringTag("hello").setName("text")));
    }

    @Test
    void testSetElementType() {
        var empty = new ListTag<>();
        empty.setElementType(TagType.STRING);
        assertSame(TagType.STRING, empty.getElementType());
        empty.setElementType(null);
        assertNull(empty.getElementType());

        var nonEmpty = new ListTag<>();
        nonEmpty.addTag(new IntTag(1).setName("number"));
        assertThrows(IllegalStateException.class, () -> nonEmpty.setElementType(null));
        assertThrows(IllegalStateException.class, () -> nonEmpty.setElementType(TagType.STRING));

        var list = new ListTag<>();
        var first = new IntTag(1).setName("a");
        var second = new IntTag(2).setName("b");
        list.addTags(first, second);
        list.setElementType(TagType.COMPOUND);

        assertSame(TagType.COMPOUND, list.getElementType());
        assertEquals(2, list.size());

        CompoundTag wrappedFirst = assertInstanceOf(CompoundTag.class, list.getTag(0));
        CompoundTag wrappedSecond = assertInstanceOf(CompoundTag.class, list.getTag(1));
        assertAttached(list, wrappedFirst, 0);
        assertAttached(list, wrappedSecond, 1);

        assertSame(first, wrappedFirst.get(""));
        assertSame(second, wrappedSecond.get(""));
        assertSame(wrappedFirst, first.getParentTag());
        assertSame(wrappedSecond, second.getParentTag());
        assertEquals(0, first.getIndex());
        assertEquals(0, second.getIndex());
        assertEquals("", first.getName());
        assertEquals("", second.getName());
    }

    @Test
    void testAddAnyTag() {
        var typedEmpty = new ListTag<>(TagType.STRING);
        var hello = new StringTag("world").setName("hello");
        typedEmpty.addAnyTag(hello);
        assertSame(TagType.STRING, typedEmpty.getElementType());
        assertEquals(1, typedEmpty.size());
        assertAttached(typedEmpty, hello, 0);

        var mismatchedEmpty = new ListTag<>();
        mismatchedEmpty.setElementType(TagType.STRING);
        var number = new IntTag(42).setName("number");
        mismatchedEmpty.addAnyTag(number);
        assertSame(TagType.INT, mismatchedEmpty.getElementType());
        assertEquals(1, mismatchedEmpty.size());
        assertAttached(mismatchedEmpty, number, 0);

        var heterogeneous = new ListTag<>();
        var intTag = new IntTag(1).setName("x");
        var stringTag = new StringTag("two").setName("y");
        heterogeneous.addAnyTag(intTag);
        heterogeneous.addAnyTag(stringTag);

        assertSame(TagType.COMPOUND, heterogeneous.getElementType());
        assertEquals(2, heterogeneous.size());

        CompoundTag first = assertInstanceOf(CompoundTag.class, heterogeneous.getTag(0));
        CompoundTag second = assertInstanceOf(CompoundTag.class, heterogeneous.getTag(1));
        assertAttached(heterogeneous, first, 0);
        assertAttached(heterogeneous, second, 1);

        assertSame(intTag, first.get(""));
        assertSame(stringTag, second.get(""));
        assertSame(first, intTag.getParentTag());
        assertSame(second, stringTag.getParentTag());
        assertEquals(0, intTag.getIndex());
        assertEquals(0, stringTag.getIndex());
    }

    @Test
    void testCloneEqualsAndIndependentChildren() {
        var tag = new ListTag<>().setName("root");
        tag.addTag(new StringTag("hello").setName("greeting"));
        tag.addTag(new StringTag("world").setName("target"));

        var sameContent = new ListTag<>(TagType.STRING).setName("ignored");
        sameContent.addTag(new StringTag("hello").setName("a"));
        sameContent.addTag(new StringTag("world").setName("b"));

        var different = new ListTag<>().setName("root");
        different.addTag(new StringTag("hello").setName("a"));
        different.addTag(new StringTag("copilot").setName("b"));

        assertContentEquals(tag, sameContent);
        assertContentNotEquals(tag, different);

        ListTag<Tag> clone = tag.clone();
        assertNotSame(tag, clone);
        assertEquals(tag, clone);
        assertContentEquals(tag, clone);
        assertNull(clone.getParent());
        assertNull(clone.getParentTag());
        assertEquals(-1, clone.getIndex());
        assertSame(TagType.STRING, clone.getElementType());
        assertNotSame(tag.getTag(0), clone.getTag(0));
        assertNotSame(tag.getTag(1), clone.getTag(1));

        StringTag cloneFirst = assertInstanceOf(StringTag.class, clone.getTag(0));
        cloneFirst.set("changed");
        assertEquals("hello", assertInstanceOf(StringTag.class, tag.getTag(0)).get());
        assertEquals("changed", cloneFirst.get());
        assertContentNotEquals(tag, clone);
    }

    @Test
    void testRemoveAt() {
        ListTag<IntTag> list = new ListTag<>();
        for (int i = 0; i < 9; i++) {
            list.addTag(new IntTag(i));
        }
        assertEquals(new IntTag(6), list.removeTagAt(6));
        assertEquals(8, list.size());
        assertArrayEquals(new IntTag[]{
                new IntTag(0), new IntTag(1), new IntTag(2),
                new IntTag(3), new IntTag(4), new IntTag(5),
                new IntTag(7), new IntTag(8), null,
                null, null, null}, list.tags);
    }

    @Test
    void testRemoveAtCapacity() {
        ListTag<IntTag> list = new ListTag<>();
        for (int i = 0; i < ArrayAccessor.DEFAULT_CAPACITY; i++) {
            list.addTag(new IntTag(i));
        }
        assertEquals(new IntTag(6), list.removeTagAt(6));
        assertEquals(ArrayAccessor.DEFAULT_CAPACITY - 1, list.size());
        assertArrayEquals(new IntTag[]{
                new IntTag(0), new IntTag(1), new IntTag(2),
                new IntTag(3), new IntTag(4), new IntTag(5),
                new IntTag(7), new IntTag(8), new IntTag(9),
                new IntTag(10), new IntTag(11), null}, list.tags);
    }

    @Test
    void testRemoveAtEnd() {
        ListTag<IntTag> list = new ListTag<>();
        for (int i = 0; i < ArrayAccessor.DEFAULT_CAPACITY; i++) {
            list.addTag(new IntTag(i));
        }
        assertEquals(new IntTag(11), list.removeTagAt(11));
        assertEquals(ArrayAccessor.DEFAULT_CAPACITY - 1, list.size());
        assertArrayEquals(new IntTag[]{
                new IntTag(0), new IntTag(1), new IntTag(2),
                new IntTag(3), new IntTag(4), new IntTag(5),
                new IntTag(6), new IntTag(7), new IntTag(8),
                new IntTag(9), new IntTag(10), null}, list.tags);
    }
}
