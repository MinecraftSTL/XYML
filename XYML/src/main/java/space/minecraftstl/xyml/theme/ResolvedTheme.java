/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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
