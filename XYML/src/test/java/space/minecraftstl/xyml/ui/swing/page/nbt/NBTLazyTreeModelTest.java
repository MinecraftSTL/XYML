// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
