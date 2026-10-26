// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;

/// Toolkit-neutral compatibility state for one installed resource pack.
@NotNullByDefault
public enum ResourcePackCompatibility {
    /// The pack declares support for the managed Minecraft version.
    COMPATIBLE,

    /// The pack targets a newer Minecraft resource format.
    TOO_NEW,

    /// The pack targets an older Minecraft resource format.
    TOO_OLD,

    /// The pack metadata contains an invalid resource-format declaration.
    INVALID,

    /// The pack has no readable `pack.mcmeta` declaration.
    MISSING_PACK_META,

    /// The managed game instance has no readable resource-format metadata.
    MISSING_GAME_META
}
