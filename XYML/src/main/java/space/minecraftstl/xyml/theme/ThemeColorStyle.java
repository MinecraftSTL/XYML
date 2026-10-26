// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.theme;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;

/// Historical theme palette-generation styles represented independently of MonetFX.
@NotNullByDefault
public enum ThemeColorStyle {
    /// Historical default style.
    DEFAULT,
    /// Tonal-spot palette.
    TONAL_SPOT,
    /// Source-fidelity palette.
    FIDELITY,
    /// Monochrome palette.
    MONOCHROME,
    /// Neutral palette.
    NEUTRAL,
    /// Vibrant palette.
    VIBRANT,
    /// Expressive palette.
    EXPRESSIVE,
    /// Content-derived palette.
    CONTENT,
    /// Rainbow palette.
    RAINBOW,
    /// Fruit-salad palette.
    FRUIT_SALAD;

    /// Parses a historical manifest token.
    ///
    /// @param value serialized style
    /// @return parsed style
    /// @throws IllegalArgumentException when the value is unsupported
    public static ThemeColorStyle parse(String value) {
        String normalized = value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        return valueOf(normalized);
    }

    /// Returns the canonical manifest representation.
    ///
    /// @return lowercase serialized value
    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
