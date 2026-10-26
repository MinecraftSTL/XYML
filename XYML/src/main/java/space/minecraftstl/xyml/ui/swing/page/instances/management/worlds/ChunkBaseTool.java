// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import org.jetbrains.annotations.NotNullByDefault;

/// Chunk Base world-analysis destinations restored from the former world-management menu.
@NotNullByDefault
public enum ChunkBaseTool {
    /// Interactive seed and biome map.
    SEED_MAP,

    /// Stronghold location finder.
    STRONGHOLD,

    /// Nether fortress location finder.
    NETHER_FORTRESS,

    /// End city location finder for supported world versions.
    END_CITY
}
