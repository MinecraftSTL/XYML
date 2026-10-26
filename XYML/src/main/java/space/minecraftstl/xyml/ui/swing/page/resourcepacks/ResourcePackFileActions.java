// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;

/// Package-private file-system boundary for ensuring the installed resource-pack directory.
@NotNullByDefault
interface ResourcePackFileActions {
    /// Creates the directory and missing parents, or leaves an existing directory unchanged.
    ///
    /// @param directory directory to ensure
    /// @throws IOException if the directory cannot be created
    void createDirectories(Path directory) throws IOException;
}
