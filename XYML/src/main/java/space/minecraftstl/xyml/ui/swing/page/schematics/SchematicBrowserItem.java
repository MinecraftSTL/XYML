// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/// One immutable directory or Litematic row returned for a viewport range.
@NotNullByDefault
public sealed interface SchematicBrowserItem permits SchematicDirectoryItem, SchematicFileItem {
    /// Returns the exact path represented by this row.
    ///
    /// @return row path
    Path path();

    /// Returns the source file name used for deterministic ordering and fallback display.
    ///
    /// @return source file name
    String fileName();
}
