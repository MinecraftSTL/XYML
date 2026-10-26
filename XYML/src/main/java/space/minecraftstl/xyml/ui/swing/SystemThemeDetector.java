// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;

/// Reads the operating-system appearance used to resolve the system theme mode.
///
/// Implementations must be fast and non-blocking because the theme manager calls them on the Swing event dispatch thread.
@FunctionalInterface
@NotNullByDefault
public interface SystemThemeDetector {
    /// Returns a deterministic light-mode detector for platforms without a reliable native appearance signal.
    ///
    /// @return a detector that always reports light appearance
    static SystemThemeDetector lightFallback() {
        return () -> false;
    }

    /// Returns whether the operating system currently requests a dark appearance.
    ///
    /// @return `true` for dark appearance and `false` for light appearance
    boolean isDarkTheme();
}
