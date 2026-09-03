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
// Added by MinecraftSTL in 2026 for transactional XoyzNBT editor coverage.
package space.minecraftstl.xyml.library.nbt.edit;

import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.IntArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.IntTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies detached ownership, structural edits, stale handles, and history semantics.
@NotNullByDefault
public final class NBTEditorTest {
    /// Ensures the editor owns a detached copy and inserted values are copied.
    @Test
    void ownsDetachedTrees() throws Exception {
        CompoundTag source = new CompoundTag().addInt("value", 1);
        NBTEditor<CompoundTag> editor = NBTEditor.of(source);

        source.setInt("value", 9);
        NBTNode value = editor.resolve(NBTAddress.root().appendName("value"));
        assertEquals("1", value.getValue());

        IntTag candidate = new IntTag(4).setName("newValue");
        editor.insertTag(editor.getRootNode(), 1, candidate);
        candidate.set(8);
        assertEquals(4, ((IntTag) editor.snapshot(editor.resolve(NBTAddress.root().appendName("newValue")))).get());
        assertNotSame(candidate, editor.snapshot(editor.resolve(NBTAddress.root().appendName("newValue"))));
    }

    /// Ensures compound, list, and array operations preserve their container invariants.
    @Test
    void performsStructuralOperations() throws Exception {
        CompoundTag source = new CompoundTag()
                .addInt("first", 1)
                .addInt("second", 2)
                .addTag("numbers", new ListTag<IntTag>(TagType.INT).addTag(new IntTag(3)));
        NBTEditor<CompoundTag> editor = NBTEditor.of(source);

        NBTNode first = editor.resolve(NBTAddress.root().appendName("first"));
        editor.rename(first, "renamed");
        NBTNode renamed = editor.resolve(NBTAddress.root().appendName("renamed"));
        assertEquals("renamed", renamed.getName());

        NBTNode root = editor.getRootNode();
        editor.move(editor.resolve(NBTAddress.root().appendName("second")), root, 0);
        assertEquals("second", editor.getChildren(editor.getRootNode()).get(0).getName());

        NBTNode list = editor.resolve(NBTAddress.root().appendName("numbers"));
        editor.insertTag(list, 1, new IntTag(4));
        assertEquals(2, editor.resolve(list.getAddress()).getChildCount());
        editor.setArrayElement(editor.resolve(list.getAddress()), 0, "7");
        assertEquals(7, ((IntTag) editor.snapshot(editor.getChild(editor.resolve(list.getAddress()), 0))).get());

        editor.remove(editor.resolve(NBTAddress.root().appendName("renamed")));
        assertThrows(NBTEditException.class, () -> editor.resolve(NBTAddress.root().appendName("renamed")));
    }

    /// Ensures failed edits are atomic and report stable reasons.
    @Test
    void rejectsInvalidEditsAtomically() throws Exception {
        CompoundTag source = new CompoundTag().addInt("value", 1);
        NBTEditor<CompoundTag> editor = NBTEditor.of(source);
        NBTNode root = editor.getRootNode();
        long revision = editor.getRevision();

        IntTag duplicate = new IntTag(2).setName("value");
        NBTEditException duplicateFailure = assertThrows(NBTEditException.class,
                () -> editor.insertTag(root, 1, duplicate));
        assertEquals(NBTEditException.Reason.DUPLICATE_NAME, duplicateFailure.reason());
        assertEquals(revision, editor.getRevision());
        assertEquals(1, ((IntTag) editor.snapshot(editor.resolve(NBTAddress.root().appendName("value")))).get());

        CompoundTag branch = new CompoundTag().setName("branch");
        branch.addTag("child", new CompoundTag());
        editor.insertTag(editor.getRootNode(), 1, branch);
        NBTNode branchNode = editor.resolve(NBTAddress.root().appendName("branch"));
        NBTNode childNode = editor.resolve(NBTAddress.root().appendName("branch").appendName("child"));
        NBTEditException cycleFailure = assertThrows(NBTEditException.class,
                () -> editor.move(branchNode, childNode, 0));
        assertEquals(NBTEditException.Reason.CYCLE, cycleFailure.reason());
        assertEquals("branch", editor.resolve(NBTAddress.root().appendName("branch")).getName());
    }

    /// Ensures stale and foreign handles cannot be applied to the working tree.
    @Test
    void invalidatesHandlesAfterMutation() throws Exception {
        NBTEditor<CompoundTag> first = NBTEditor.of(new CompoundTag().addInt("value", 1));
        NBTEditor<CompoundTag> second = NBTEditor.of(new CompoundTag().addInt("value", 1));
        NBTNode old = first.resolve(NBTAddress.root().appendName("value"));

        first.setScalar(old, "2");
        NBTEditException stale = assertThrows(NBTEditException.class, () -> first.setScalar(old, "3"));
        assertEquals(NBTEditException.Reason.STALE_NODE, stale.reason());

        NBTEditException foreign = assertThrows(NBTEditException.class, () -> second.setScalar(old, "3"));
        assertEquals(NBTEditException.Reason.FOREIGN_NODE, foreign.reason());
    }

    /// Ensures undo, redo, and revision-labelled savepoints preserve clean-state semantics.
    @Test
    void supportsHistoryAndSavepoints() throws Exception {
        NBTEditor<CompoundTag> editor = NBTEditor.of(new CompoundTag().addInt("value", 1));
        NBTSavepoint<CompoundTag> savepoint = editor.saveSnapshot();
        NBTNode value = editor.resolve(NBTAddress.root().appendName("value"));

        editor.setScalar(value, "2");
        assertTrue(editor.isDirty());
        assertFalse(editor.markSaved(savepoint));
        editor.undo();
        assertFalse(editor.isDirty());
        editor.redo();
        assertTrue(editor.isDirty());
        assertTrue(editor.canUndo());
        assertFalse(editor.canRedo());
    }

    /// Ensures primitive-array values are edited through the same checked node API.
    @Test
    void editsPrimitiveArrayElements() throws Exception {
        NBTEditor<CompoundTag> editor = NBTEditor.of(new CompoundTag()
                .addTag("values", new IntArrayTag(new int[]{1, 2, 3})));
        NBTNode array = editor.resolve(NBTAddress.root().appendName("values"));
        NBTNode element = editor.getChild(array, 1);
        editor.setScalar(element, "20");

        IntArrayTag result = (IntArrayTag) editor.snapshot(editor.resolve(array.getAddress()));
        assertEquals(20, result.get(1));
    }

    /// Ensures an empty fixed Region slot accepts exactly one detached compound root.
    @Test
    void insertsAndRemovesChunkRootsTransactionally() throws Exception {
        NBTEditor<ChunkRegion> editor = NBTEditor.of(new ChunkRegion());
        NBTAddress chunkAddress = NBTAddress.root().appendChunk(37);
        CompoundTag candidate = new CompoundTag().addInt("DataVersion", 1);

        editor.insertTag(editor.resolve(chunkAddress), 0, candidate);
        candidate.setInt("DataVersion", 9);
        NBTAddress rootAddress = chunkAddress.appendChunkRoot();
        CompoundTag inserted = (CompoundTag) editor.snapshot(rootAddress);
        assertEquals(1, inserted.getInt("DataVersion"));

        long revision = editor.getRevision();
        NBTEditException duplicate = assertThrows(NBTEditException.class,
                () -> editor.insertTag(editor.resolve(chunkAddress), 0, new CompoundTag()));
        assertEquals(NBTEditException.Reason.INVALID_TARGET, duplicate.reason());
        NBTEditException wrongType = assertThrows(NBTEditException.class,
                () -> editor.insertTag(editor.resolve(chunkAddress), 0, new IntTag(1)));
        assertEquals(NBTEditException.Reason.TYPE_MISMATCH, wrongType.reason());
        assertEquals(revision, editor.getRevision());
        assertEquals(inserted, editor.snapshot(rootAddress));

        editor.remove(rootAddress);
        assertEquals(0, editor.resolve(chunkAddress).getChildCount());
        editor.undo();
        assertEquals(inserted, editor.snapshot(rootAddress));
        editor.redo();
        assertEquals(0, editor.resolve(chunkAddress).getChildCount());
    }
}
