// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.servers;

import org.jetbrains.annotations.NotNullByDefault;

/// Lifecycle state of one server-list operation.
@NotNullByDefault
public enum ServerCatalogStatus {
    /// Background load or write is active.
    LOADING,
    /// The list is available.
    READY,
    /// The last operation failed.
    FAILURE
}
