// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;

/// Lifecycle of one lazily loaded local resource-pack catalog.
@NotNullByDefault
public enum ResourcePackCatalogStatus {
    /// No disk scan has been requested.
    IDLE,

    /// The latest disk-scan generation is active.
    LOADING,

    /// The latest disk scan completed and its catalog is current.
    READY,

    /// The managed Minecraft version predates resource-pack support.
    UNSUPPORTED,

    /// The latest disk scan failed and may be retried.
    FAILED
}
