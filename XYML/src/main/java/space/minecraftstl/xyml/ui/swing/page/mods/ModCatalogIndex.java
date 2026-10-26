// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Complete immutable local Mod index produced by one `ModManager` refresh.
///
/// @param entries sorted current Mod entries
@NotNullByDefault
record ModCatalogIndex(@Unmodifiable List<ModCatalogEntry> entries) {
    /// Stores a defensive immutable entry list.
    ModCatalogIndex {
        entries = List.copyOf(entries);
    }
}
