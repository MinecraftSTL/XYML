// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
