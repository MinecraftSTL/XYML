// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes the most recently observed vertical scrolling direction.
@NotNullByDefault
public enum ScrollDirection {
    /// The viewport has not moved between observations.
    STATIONARY,

    /// The viewport moved toward lower item indexes.
    UP,

    /// The viewport moved toward higher item indexes.
    DOWN
}
