// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects which game-version kinds remain visible in the local catalog snapshot.
@NotNullByDefault
public enum GameVersionFilter {
    /// Includes every game-version kind.
    ALL,

    /// Includes only normal releases.
    RELEASE,

    /// Includes only snapshots and pre-releases.
    SNAPSHOT,

    /// Includes only April Fools' Day versions.
    APRIL_FOOLS,

    /// Includes only historical versions.
    OLD;

    /// Returns whether one kind passes this filter.
    ///
    /// @param kind version kind to test
    /// @return whether the kind is visible
    public boolean includes(GameVersionKind kind) {
        return this == ALL || name().equals(kind.name());
    }
}
