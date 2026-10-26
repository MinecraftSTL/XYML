// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/// Immutable child-directory row that requires no Litematic parsing.
///
/// @param path exact known child path
/// @param fileName child directory name
@NotNullByDefault
public record SchematicDirectoryItem(Path path, String fileName) implements SchematicBrowserItem {
}
