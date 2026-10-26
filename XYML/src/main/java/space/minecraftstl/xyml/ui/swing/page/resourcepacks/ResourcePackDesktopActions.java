// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;

/// Package-private platform desktop boundary for resource-pack reveal and directory-open tests.
@NotNullByDefault
interface ResourcePackDesktopActions {
    /// Returns whether the platform desktop supports one action.
    ///
    /// @param action action queried
    /// @return whether the action is supported
    boolean isSupported(Desktop.Action action);

    /// Reveals the exact resource-pack path with the dedicated platform action.
    ///
    /// @param target resource-pack path to reveal
    /// @throws IOException if platform integration fails
    void browseFileDirectory(Path target) throws IOException;

    /// Opens one directory with the platform handler.
    ///
    /// @param directory directory to open
    /// @throws IOException if platform integration fails
    void open(Path directory) throws IOException;
}
