// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;

/// Lifecycle of the single serialized Mod file mutation.
@NotNullByDefault
public enum ModCatalogWriteStatus {
    /// No write is running and the latest write succeeded.
    IDLE,

    /// One enable, disable, import, or delete operation is running.
    BUSY,

    /// The latest write or its mandatory follow-up refresh failed.
    ERROR
}
