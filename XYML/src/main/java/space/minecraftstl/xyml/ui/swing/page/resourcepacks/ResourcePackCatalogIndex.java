// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.List;

/// Shallow source result containing no parsed pack metadata.
///
/// Enabled installed packs occupy the leading paths in descending priority order. The remaining
/// paths are disabled packs in deterministic file-name order.
///
/// @param supported whether the Minecraft instance supports resource packs
/// @param paths candidate direct children in display order, empty when unsupported
/// @param enabledPathCount number of leading paths whose identifiers are enabled
@NotNullByDefault
record ResourcePackCatalogIndex(
        boolean supported,
        @Unmodifiable List<Path> paths,
        int enabledPathCount) {
    /// Stores a defensive path-list copy and validates supported counts.
    ResourcePackCatalogIndex {
        paths = List.copyOf(paths);
        if (enabledPathCount < 0 || enabledPathCount > paths.size()) {
            throw new IllegalArgumentException("enabledPathCount must be inside paths");
        }
        if (!supported && (!paths.isEmpty() || enabledPathCount != 0)) {
            throw new IllegalArgumentException("Unsupported index must not contain paths or enabled entries");
        }
    }

    /// Creates one index with no known enabled prefix.
    ///
    /// @param supported whether the instance supports resource packs
    /// @param paths deterministic candidate paths
    ResourcePackCatalogIndex(boolean supported, @Unmodifiable List<Path> paths) {
        this(supported, paths, 0);
    }
}
