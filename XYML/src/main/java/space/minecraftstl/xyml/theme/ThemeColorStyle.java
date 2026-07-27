/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
