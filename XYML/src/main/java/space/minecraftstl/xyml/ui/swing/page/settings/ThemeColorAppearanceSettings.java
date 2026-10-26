// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.theme.ThemeColor;

import java.util.Objects;

/// Immutable custom launcher theme-color seed and selected-theme override state.
///
/// @param customColor persisted custom launcher accent seed
/// @param overridden whether the custom launcher color overrides the selected theme pack
@NotNullByDefault
public record ThemeColorAppearanceSettings(
        ThemeColor customColor,
        boolean overridden) {
    /// Creates the launcher defaults with the color inherited from the selected theme.
    ///
    /// @return default non-overridden theme-color settings
    public static ThemeColorAppearanceSettings defaults() {
        return new ThemeColorAppearanceSettings(
                ThemeColor.DEFAULT,
                false);
    }

    /// Rejects missing values before they reach theme resolution or persistence.
    public ThemeColorAppearanceSettings {
        Objects.requireNonNull(customColor, "customColor");
    }
}
