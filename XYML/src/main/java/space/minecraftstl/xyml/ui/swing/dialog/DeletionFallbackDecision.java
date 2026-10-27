// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.dialog;

import org.jetbrains.annotations.NotNullByDefault;

/// User choice after a target could not be moved to the platform recycle bin.
@NotNullByDefault
public enum DeletionFallbackDecision {
    /// Attempt the same targets through the recycle-bin boundary again.
    RETRY_RECYCLE_BIN,

    /// Permanently delete the targets that remain after the recycle-bin attempt.
    DELETE_PERMANENTLY,

    /// Leave every remaining target untouched.
    CANCEL
}
