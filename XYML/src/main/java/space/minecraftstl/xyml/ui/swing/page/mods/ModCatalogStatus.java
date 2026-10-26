// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;

/// Lifecycle of the background local Mod index.
@NotNullByDefault
public enum ModCatalogStatus {
    /// No local index has been requested.
    IDLE,

    /// The latest full `ModManager` refresh is running.
    LOADING,

    /// The filtered index is current and has an exact row count.
    READY,

    /// The latest refresh failed and may be retried.
    FAILED
}
