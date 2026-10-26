// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes the availability of the shallow world-directory index.
@NotNullByDefault
public enum WorldCatalogStatus {
    /// The page has not requested a directory scan yet.
    IDLE,

    /// A background shallow directory scan is in progress.
    LOADING,

    /// The exact directory count and index are available for viewport reads.
    READY,

    /// The latest shallow directory scan failed and can be retried.
    FAILED
}
