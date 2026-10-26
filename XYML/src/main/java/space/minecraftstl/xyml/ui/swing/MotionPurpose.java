// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;

/// Classifies whether motion carries information or only adds visual polish.
@NotNullByDefault
public enum MotionPurpose {
    /// Communicates state or preserves spatial continuity and remains enabled under reduced motion.
    ESSENTIAL,

    /// Adds visual polish and is skipped under reduced motion.
    DECORATIVE
}
