// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Movement cycle rendered by the software skin preview.
@NotNullByDefault
enum SkinPreviewMotion {
    /// A standing or posture-specific idle cycle.
    IDLE,

    /// A normal alternating walk cycle.
    WALKING,

    /// A faster upright run cycle.
    SPRINTING
}
