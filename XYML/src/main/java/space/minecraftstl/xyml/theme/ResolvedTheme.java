// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.theme;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Concrete theme values that presentation-toolkit adapters can translate into their own tokens.
///
/// @param primaryColorSeed canonical color seed
/// @param brightness effective brightness
/// @param colorStyle historical palette-generation style
/// @param contrast normalized contrast
@NotNullByDefault
public record ResolvedTheme(
        ThemeColor primaryColorSeed,
        ThemeBrightness brightness,
        ThemeColorStyle colorStyle,
        ThemeContrast contrast) {
    /// Launcher fallback values used when a theme omits appearance fields.
    public static final ResolvedTheme DEFAULT = new ResolvedTheme(
            ThemeColor.DEFAULT,
            ThemeBrightness.LIGHT,
            ThemeColorStyle.FIDELITY,
            ThemeContrast.STANDARD);

    /// Validates the resolved values.
    public ResolvedTheme {
        Objects.requireNonNull(primaryColorSeed, "primaryColorSeed");
        Objects.requireNonNull(brightness, "brightness");
        Objects.requireNonNull(colorStyle, "colorStyle");
        Objects.requireNonNull(contrast, "contrast");
    }
}
