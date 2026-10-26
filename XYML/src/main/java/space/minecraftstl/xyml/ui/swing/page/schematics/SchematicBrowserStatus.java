// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes the lifecycle of the current shallow schematic directory snapshot.
@NotNullByDefault
public enum SchematicBrowserStatus {
    /// No directory scan has been requested.
    IDLE,

    /// A caller-owned executor is scanning a directory.
    LOADING,

    /// The current directory has an exact, stable indexed listing.
    READY,

    /// The latest directory scan failed while the previous stable listing, if any, was retained.
    ERROR
}
