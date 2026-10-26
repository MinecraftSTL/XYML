// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies one content category in the download center.
@NotNullByDefault
public enum DownloadPageTarget {
    /// Curated modpack archives.
    MODPACK,

    /// Instance Mods.
    MODS,

    /// Instance resource packs.
    RESOURCE_PACKS,

    /// Instance shader packs.
    SHADERS,

    /// World archives and remote world discovery.
    WORLDS
}
