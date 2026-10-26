// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;

/// Enabled-state filter applied to the current local Mod index.
@NotNullByDefault
public enum ModCatalogFilter {
    /// Includes every indexed current Mod file.
    ALL,

    /// Includes only files that Minecraft will load.
    ENABLED,

    /// Includes only files carrying the disabled suffix.
    DISABLED;

    /// Returns whether one enabled state belongs to this filter.
    ///
    /// @param enabled whether the indexed Mod is active on disk
    /// @return whether the row should remain visible
    public boolean matches(boolean enabled) {
        return this == ALL || (this == ENABLED) == enabled;
    }
}
