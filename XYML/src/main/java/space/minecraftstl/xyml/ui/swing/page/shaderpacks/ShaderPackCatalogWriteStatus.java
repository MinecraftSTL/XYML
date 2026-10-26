// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;

/// Lifecycle of one serialized shader-pack catalog mutation.
@NotNullByDefault
public enum ShaderPackCatalogWriteStatus {
    /// No mutation is active.
    IDLE,

    /// A mutation is active.
    BUSY,

    /// The latest mutation failed.
    FAILED
}
