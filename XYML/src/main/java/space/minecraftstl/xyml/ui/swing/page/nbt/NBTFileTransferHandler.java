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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.shell.ShellFileDropHandler;

import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/// Decodes file-list transfers and forwards immutable normalized paths to the NBT editor page.
@NotNullByDefault
final class NBTFileTransferHandler extends TransferHandler {
    /// Serialization identifier for the Swing transfer superclass.
    @Serial
    private static final long serialVersionUID = 1L;

    /// Reports whether the editor can currently accept a file replacement.
    private final BooleanSupplier acceptsFiles;

    /// Receives a fully decoded immutable path list on the EDT.
    private final Function<@Unmodifiable List<Path>, Boolean> opener;

    /// Creates one transfer boundary.
    ///
    /// @param acceptsFiles current acceptance predicate
    /// @param opener immutable path consumer
    NBTFileTransferHandler(
            BooleanSupplier acceptsFiles,
            Function<@Unmodifiable List<Path>, Boolean> opener) {
        this.acceptsFiles = Objects.requireNonNull(acceptsFiles, "acceptsFiles");
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    /// Reports whether shell text or an available Java file list can be considered.
    ///
    /// @param support Swing transfer context
    /// @return whether decoding may proceed
    @Override
    public boolean canImport(TransferSupport support) {
        TransferSupport transferSupport = Objects.requireNonNull(support, "support");
        return ShellFileDropHandler.canImportAncestorText(transferSupport)
                || (acceptsFiles.getAsBoolean()
                && transferSupport.isDataFlavorSupported(DataFlavor.javaFileListFlavor));
    }

    /// Decodes and forwards one file-list transfer.
    ///
    /// @param support Swing transfer context
    /// @return whether one source was accepted
    @Override
    public boolean importData(TransferSupport support) {
        TransferSupport transferSupport = Objects.requireNonNull(support, "support");
        if (ShellFileDropHandler.importAncestorText(transferSupport)) {
            return true;
        }
        if (!canImport(transferSupport)) {
            return false;
        }
        @Nullable @Unmodifiable List<Path> paths = transferredPaths(transferSupport.getTransferable());
        return paths != null && opener.apply(paths);
    }

    /// Decodes Java file-list transfers without filesystem access.
    ///
    /// @param transferable transfer payload
    /// @return immutable paths, or `null` when unsupported
    private static @Nullable @Unmodifiable List<Path> transferredPaths(Transferable transferable) {
        Transferable payload = Objects.requireNonNull(transferable, "transferable");
        if (!payload.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return null;
        }
        try {
            Object transferData = payload.getTransferData(DataFlavor.javaFileListFlavor);
            if (!(transferData instanceof List<?> files)) {
                return null;
            }
            List<Path> paths = new ArrayList<>(files.size());
            for (Object value : files) {
                if (!(value instanceof File file)) {
                    return null;
                }
                paths.add(file.toPath().toAbsolutePath().normalize());
            }
            return List.copyOf(paths);
        } catch (UnsupportedFlavorException | IOException | RuntimeException failure) {
            return null;
        }
    }
}
