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
package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.swing.JList;
import javax.swing.TransferHandler;
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/// Verifies actual page-installed file drag payloads without native windows or filesystem copying.
@NotNullByDefault
public final class FileDragAssertions {
    /// Prevents construction of the test utility.
    private FileDragAssertions() {
    }

    /// Arms the selected row body and returns its current native file payload.
    ///
    /// @param list loaded explicitly opted-in list with a selected row
    /// @return file-only transfer for the selected rows
    public static Transferable capture(JList<?> list) {
        Rectangle bounds = Objects.requireNonNull(list.getCellBounds(list.getSelectedIndex(), list.getSelectedIndex()));
        MouseEvent press = new MouseEvent(list, MouseEvent.MOUSE_PRESSED, 1L,
                MouseEvent.BUTTON1_DOWN_MASK, bounds.x + 8, bounds.y + bounds.height / 2,
                1, false, MouseEvent.BUTTON1);
        for (MouseListener listener : list.getMouseListeners()) {
            if (listener instanceof FileListDragSupport<?> dragging) {
                dragging.mousePressed(press);
                @Nullable Transferable transfer = dragging.pendingTransfer();
                assertNotNull(transfer, "The selected loaded row must supply a current local file path");
                return transfer;
            }
        }
        return fail("File drag support is not installed on this intended catalog");
    }

    /// Verifies that actual selected page rows expose only immutable file references and COPY semantics.
    ///
    /// @param list loaded list with the intended selection already applied
    /// @param expected actual selected paths in displayed order
    public static void assertFiles(JList<?> list, @Unmodifiable List<Path> expected) {
        Transferable transfer = capture(list);
        assertArrayEquals(new DataFlavor[] {DataFlavor.javaFileListFlavor}, transfer.getTransferDataFlavors());
        assertEquals(TransferHandler.COPY, Objects.requireNonNull(list.getTransferHandler()).getSourceActions(list));
        try {
            assertEquals(expected.stream().map(path -> path.toAbsolutePath().normalize().toFile()).toList(),
                    transfer.getTransferData(DataFlavor.javaFileListFlavor));
        } catch (java.awt.datatransfer.UnsupportedFlavorException | java.io.IOException failure) {
            throw new AssertionError(failure);
        }
        assertFalse(list.getTransferHandler().canImport(new TransferHandler.TransferSupport(list, transfer)));
    }
}
