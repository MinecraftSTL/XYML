// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;

/// Package-private platform desktop boundary used to test reveal behavior without opening files.
@NotNullByDefault
interface DesktopActions {
    /// Returns whether the platform desktop supports one action.
    ///
    /// @param action action queried
    /// @return whether the action is supported
    boolean isSupported(Desktop.Action action);

    /// Reveals the exact target using the dedicated browse-file-directory action.
    ///
    /// @param target row path to reveal
    /// @throws IOException if the desktop integration fails
    void browseFileDirectory(Path target) throws IOException;

    /// Opens one directory with the platform default handler.
    ///
    /// @param directory directory to open
    /// @throws IOException if the desktop integration fails
    void open(Path directory) throws IOException;
}
