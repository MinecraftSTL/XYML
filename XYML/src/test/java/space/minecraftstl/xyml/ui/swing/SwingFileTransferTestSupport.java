// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import javax.swing.JComponent;
import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Creates standard local-file transfer payloads for headless Swing drag-and-drop tests.
@NotNullByDefault
public final class SwingFileTransferTestSupport {
    /// Prevents construction of this test utility.
    private SwingFileTransferTestSupport() {
    }

    /// Wraps paths in a standard Java file-list transfer targeted at one component.
    ///
    /// @param component transfer target
    /// @param paths immutable local paths exposed in transfer order
    /// @return transfer support using [DataFlavor#javaFileListFlavor]
    public static TransferHandler.TransferSupport fileTransfer(
            JComponent component,
            @Unmodifiable List<Path> paths) {
        List<File> files = Objects.requireNonNull(paths, "paths").stream()
                .map(Path::toFile)
                .toList();
        return new TransferHandler.TransferSupport(
                Objects.requireNonNull(component, "component"),
                new FileListTransferable(files));
    }

    /// Immutable Java file-list transferable.
    @NotNullByDefault
    private static final class FileListTransferable implements Transferable {
        /// Immutable local files exposed by this payload.
        private final @Unmodifiable List<File> files;

        /// Creates one immutable local-file payload.
        ///
        /// @param files immutable local files
        private FileListTransferable(@Unmodifiable List<File> files) {
            this.files = List.copyOf(Objects.requireNonNull(files, "files"));
        }

        /// Returns the single standard file-list flavor.
        ///
        /// @return defensive flavor array
        @Override
        public DataFlavor @Unmodifiable [] getTransferDataFlavors() {
            return new DataFlavor[] {DataFlavor.javaFileListFlavor};
        }

        /// Reports support only for the standard file-list flavor.
        ///
        /// @param flavor requested flavor
        /// @return whether the flavor is the Java file-list flavor
        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(Objects.requireNonNull(flavor, "flavor"));
        }

        /// Returns the immutable file list for the supported flavor.
        ///
        /// @param flavor requested flavor
        /// @return immutable local file list
        /// @throws UnsupportedFlavorException when another flavor is requested
        @Override
        public @Unmodifiable List<File> getTransferData(DataFlavor flavor)
                throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return files;
        }
    }
}
