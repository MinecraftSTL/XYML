// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Body posture rendered by the software skin preview.
@NotNullByDefault
enum SkinPreviewPosture {
    /// Ordinary standing posture.
    STANDING,

    /// Crouched posture with a forward torso lean.
    SNEAKING,

    /// Seated posture with arms and legs extended forward.
    RIDING,

    /// Minecraft 1.21 swimming pose, also used by block-constrained crawling.
    SWIMMING
}
