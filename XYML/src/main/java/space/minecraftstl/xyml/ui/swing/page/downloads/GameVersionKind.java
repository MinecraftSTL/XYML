// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;

/// Classifies one Minecraft game version independently of presentation text.
@NotNullByDefault
public enum GameVersionKind {
    /// A normal stable Minecraft release.
    RELEASE,

    /// A development snapshot or pre-release build.
    SNAPSHOT,

    /// An April Fools' Day special version.
    APRIL_FOOLS,

    /// A historical version predating the normal release channels.
    OLD
}
