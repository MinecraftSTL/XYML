// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util.io;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects how a user-visible deletion removes its targets.
@NotNullByDefault
public enum DeletionMode {
    /// Attempts to move every target to the platform recycle bin before any permanent deletion.
    RECYCLE_BIN_FIRST,

    /// Permanently removes targets without attempting the recycle bin.
    PERMANENT
}
