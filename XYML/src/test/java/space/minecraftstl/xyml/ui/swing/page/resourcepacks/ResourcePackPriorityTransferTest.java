// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies priority moves have no external file or text representation.
@NotNullByDefault
public final class ResourcePackPriorityTransferTest {
    /// Only the JVM-local flavor exposes the priority identity; native recipients cannot copy or move files from it.
    @Test
    public void priorityPayloadIsLocalOnlyAndRejectsFilesAndText() throws Exception {
        ResourcePackCatalogReorderSupport.ReorderTransferable transfer =
                new ResourcePackCatalogReorderSupport.ReorderTransferable(new TransferHandler("fixture"), Path.of("one.zip"));
        DataFlavor[] flavors = transfer.getTransferDataFlavors();
        assertEquals(1, flavors.length);
        assertTrue(flavors[0].isMimeTypeEqual(DataFlavor.javaJVMLocalObjectMimeType));
        assertFalse(transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor));
        assertFalse(transfer.isDataFlavorSupported(DataFlavor.stringFlavor));
        assertSame(transfer, transfer.getTransferData(flavors[0]));
        assertThrows(UnsupportedFlavorException.class, () -> transfer.getTransferData(DataFlavor.javaFileListFlavor));
        flavors[0] = DataFlavor.stringFlavor;
        assertTrue(transfer.getTransferDataFlavors()[0].isMimeTypeEqual(DataFlavor.javaJVMLocalObjectMimeType));
    }
}
