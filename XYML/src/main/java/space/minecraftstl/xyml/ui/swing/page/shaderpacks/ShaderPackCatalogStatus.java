// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;

/// Lifecycle of one lazily loaded local shader-pack catalog.
@NotNullByDefault
public enum ShaderPackCatalogStatus {
    /// No disk scan has been requested.
    IDLE,

    /// The latest disk scan is active.
    LOADING,

    /// The latest disk scan completed and its catalog is current.
    READY,

    /// The latest disk scan failed and may be retried.
    FAILED
}
