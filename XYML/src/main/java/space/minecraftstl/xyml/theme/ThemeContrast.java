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

/// Toolkit-neutral contrast level in the inclusive `-1` to `1` range.
///
/// @param value normalized contrast value
@NotNullByDefault
public record ThemeContrast(double value) {
    /// Low-contrast preset.
    public static final ThemeContrast LOW = new ThemeContrast(-1.0);

    /// Standard contrast preset.
    public static final ThemeContrast STANDARD = new ThemeContrast(0.0);

    /// Medium-contrast preset.
    public static final ThemeContrast MEDIUM = new ThemeContrast(0.5);

    /// High-contrast preset.
    public static final ThemeContrast HIGH = new ThemeContrast(1.0);

    /// Validates one contrast value.
    ///
    /// @param value normalized contrast value
    public ThemeContrast {
        if (!Double.isFinite(value) || value < -1.0 || value > 1.0) {
            throw new IllegalArgumentException("Theme contrast must be between -1 and 1: " + value);
        }
    }
}
