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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.edit.NBTAddress;
import space.minecraftstl.xyml.library.nbt.io.NBTCodec;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.nbt.NBTDocument;
import space.minecraftstl.xyml.nbt.NBTDocumentService;

import javax.swing.tree.TreePath;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Verifies that Swing tree queries retain the backend's one-index-at-a-time behavior.
@NotNullByDefault
final class NBTLazyTreeModelTest {
    /// Temporary source directory for a real region fixture.
    @TempDir
    private Path temporaryDirectory;

    /// Counts all region slots without constructing chunks and resolves only one requested path.
    @Test
    void keepsRegionExpansionIndexedAndLazy() throws Exception {
        Path source = temporaryDirectory.resolve("r.0.0.mca");
        ChunkRegion region = new ChunkRegion();
        region.setChunk(1023, new Chunk(new CompoundTag().addInt("DataVersion", 3953)));
        NBTCodec.of().writeRegion(source, region);
        try (NBTDocument document = new NBTDocumentService(Runnable::run).open(source).join()) {
            NBTLazyTreeModel model = new NBTLazyTreeModel(document);
            NBTEditorTreeNode root = model.getRoot();

            assertEquals(1024, model.getChildCount(root));
            assertEquals(0, root.materializedChildCount());
            NBTEditorTreeNode chunk = model.getChild(root, 1023);
            assertEquals(1, root.materializedChildCount());
            assertEquals("Chunk (31, 31)", chunk.presentation().displayName());
            assertFalse(chunk.editable());
            assertEquals(1, model.getChildCount(chunk));
            assertEquals(0, chunk.materializedChildCount());

            NBTAddress chunkRootAddress = NBTAddress.root().appendChunk(1023).appendChunkRoot();
            TreePath chunkRootPath = model.pathForAddress(chunkRootAddress);
            NBTEditorTreeNode chunkRoot = (NBTEditorTreeNode) chunkRootPath.getLastPathComponent();
            assertEquals(1, model.getChildCount(chunkRoot));
            assertEquals(1, chunk.materializedChildCount());

            TreePath valuePath = model.pathForAddress(chunkRootAddress.appendName("DataVersion"));
            NBTEditorTreeNode value = (NBTEditorTreeNode) valuePath.getLastPathComponent();
            assertEquals("DataVersion", value.presentation().displayName());
            assertEquals("3953", value.currentScalarValue());
            assertEquals(1, chunkRoot.materializedChildCount());
            assertSame(chunk, valuePath.getPathComponent(1));
            assertSame(chunkRoot, valuePath.getPathComponent(2));
            assertEquals(1023, model.getIndexOfChild(root, chunk));
            assertEquals(0, model.getIndexOfChild(chunk, chunkRoot));
        }
    }
}
