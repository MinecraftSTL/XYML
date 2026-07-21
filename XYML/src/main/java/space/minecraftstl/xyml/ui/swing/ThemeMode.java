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
package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Describes how the launcher selects its light or dark Swing palette.
@NotNullByDefault
public enum ThemeMode {
    /// Always selects the light palette.
    LIGHT,

    /// Always selects the dark palette.
    DARK,

    /// Follows the appearance reported by the operating-system detector.
    SYSTEM;

    /// Resolves this preference to a concrete palette.
    ///
    /// @param systemThemeDetector the detector used only by {@link #SYSTEM}
    /// @return the concrete palette to render
    public ThemeVariant resolve(SystemThemeDetector systemThemeDetector) {
        Objects.requireNonNull(systemThemeDetector);

        return switch (this) {
            case LIGHT -> ThemeVariant.LIGHT;
            case DARK -> ThemeVariant.DARK;
            case SYSTEM -> systemThemeDetector.isDarkTheme() ? ThemeVariant.DARK : ThemeVariant.LIGHT;
        };
    }
}
