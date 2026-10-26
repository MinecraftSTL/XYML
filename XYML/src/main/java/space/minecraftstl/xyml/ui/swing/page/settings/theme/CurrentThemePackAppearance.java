// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.theme.ResolvedTheme;
import space.minecraftstl.xyml.ui.swing.SwingWindowAppearanceRequest;

import java.util.Objects;

/// One consistent renderer-ready appearance captured before a current-theme export starts.
///
/// @param theme concrete color, brightness, palette style, and contrast
/// @param window concrete background source and opacity
@NotNullByDefault
public record CurrentThemePackAppearance(
        ResolvedTheme theme,
        SwingWindowAppearanceRequest window) {
    /// Rejects incomplete appearance captures.
    public CurrentThemePackAppearance {
        Objects.requireNonNull(theme, "theme");
        Objects.requireNonNull(window, "window");
    }
}
