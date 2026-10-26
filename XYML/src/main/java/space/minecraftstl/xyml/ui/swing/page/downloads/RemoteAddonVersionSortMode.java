// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;

/// User-selectable ordering for installable versions returned by a remote project.
@NotNullByDefault
public enum RemoteAddonVersionSortMode {
    /// Keeps the launcher recommendation first, then applies stable compatibility ordering.
    RECOMMENDED,

    /// Groups versions by compatible Minecraft version before comparing mod versions.
    GAME_VERSION,

    /// Shows the newest mod version first while retaining exact requested-game-version priority.
    MOD_VERSION
}
