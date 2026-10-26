// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;

/// Lifecycle status of the local theme-pack inventory.
@NotNullByDefault
public enum ThemePackManagementStatus {
    /// No inventory request has started.
    IDLE,

    /// A caller-owned worker is loading the inventory.
    LOADING,

    /// The latest inventory or mutation completed successfully.
    READY,

    /// The latest inventory request or mutation failed.
    FAILED,

    /// The model is terminal and rejects new work.
    CLOSED
}
