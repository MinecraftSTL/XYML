// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes the presentation state of one lazily loaded choice row.
@NotNullByDefault
public enum ChoiceLoadStatus {
    /// The row is waiting for its current data-source request.
    LOADING,

    /// The row contains a successfully loaded choice value.
    LOADED,

    /// The data-source request covering the row failed.
    ERROR
}
