// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;

/// User-selected handling for one conflicting Mod import source.
@NotNullByDefault
public enum ModImportConflictAction {
    /// Replaces every current Mod file with the same rename-stable local key.
    REPLACE,

    /// Leaves the current Mod unchanged and does not copy the new source.
    SKIP,

    /// Keeps both Mods by appending hyphens to the new file's base name until it is unique.
    KEEP
}
