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
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// User-controlled renderer-independent values applied after a selected theme has resolved.
///
/// @param brightnessPreference four-state brightness preference
/// @param color color override, or `null` to retain the theme color
/// @param colorStyle color-style override, or `null` to retain the theme style
/// @param contrast contrast override, or `null` to retain the theme contrast
@NotNullByDefault
public record ThemeUserAppearanceOverrides(
        ThemeBrightnessPreference brightnessPreference,
        @Nullable ThemeColorSource color,
        @Nullable ThemeColorStyle colorStyle,
        @Nullable ThemeContrast contrast) {
    /// Shared value that inherits every selected-theme appearance field.
    public static final ThemeUserAppearanceOverrides INHERIT_THEME = new ThemeUserAppearanceOverrides(
            ThemeBrightnessPreference.THEME,
            null,
            null,
            null);

    /// Validates the required brightness preference.
    public ThemeUserAppearanceOverrides {
        Objects.requireNonNull(brightnessPreference, "brightnessPreference");
    }

    /// Applies these values after theme resolution.
    ///
    /// @param theme resolved selected-theme values
    /// @param systemBrightness current operating-system brightness
    /// @return concrete effective values
    public ResolvedTheme apply(ResolvedTheme theme, ThemeBrightness systemBrightness) {
        Objects.requireNonNull(theme, "theme");
        Objects.requireNonNull(systemBrightness, "systemBrightness");
        return new ResolvedTheme(
                color != null ? color.resolveFallback() : theme.primaryColorSeed(),
                brightnessPreference.resolve(theme.brightness(), systemBrightness),
                colorStyle != null ? colorStyle : theme.colorStyle(),
                contrast != null ? contrast : theme.contrast());
    }
}
