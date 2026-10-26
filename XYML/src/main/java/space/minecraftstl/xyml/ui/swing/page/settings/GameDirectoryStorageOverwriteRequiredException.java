// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;

/// Signals that a game-directory mutation requires explicit backup-and-overwrite consent.
///
/// The Swing page catches this precise failure and may retry the same operation after the user confirms. The actual
/// backup and overwrite is still performed through [space.minecraftstl.xyml.setting.GameDirectoryManager].
@NotNullByDefault
public final class GameDirectoryStorageOverwriteRequiredException extends IllegalStateException {
    /// Creates the read-only storage confirmation requirement.
    public GameDirectoryStorageOverwriteRequiredException() {
        super("Game directory storage requires backup and overwrite confirmation");
    }
}
