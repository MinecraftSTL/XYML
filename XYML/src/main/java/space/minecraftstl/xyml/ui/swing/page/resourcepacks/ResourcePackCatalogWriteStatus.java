// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;

/// Lifecycle of the catalog's single serialized local write operation.
@NotNullByDefault
public enum ResourcePackCatalogWriteStatus {
    /// No write is active and the latest write, if any, succeeded.
    IDLE,

    /// One import, enabled-state update, or deletion is active.
    BUSY,

    /// The latest write or its mandatory follow-up index scan failed.
    ERROR
}
