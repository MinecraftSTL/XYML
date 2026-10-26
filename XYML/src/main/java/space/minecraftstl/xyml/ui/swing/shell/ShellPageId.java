// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies the persistent instance workspace and its top-level destinations.
@NotNullByDefault
public enum ShellPageId {
    /// The persistent installed-instance management workspace.
    INSTANCES,

    /// The game and mod download destination.
    DOWNLOADS,

    /// The session task-management destination.
    TASKS,

    /// The authenticated player account destination.
    ACCOUNTS,

    /// The launcher preference destination.
    SETTINGS
}
