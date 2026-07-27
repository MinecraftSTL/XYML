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

import java.util.Locale;
import java.util.Objects;

/// Describes whether launcher brightness follows its theme, the operating system, or an explicit palette.
@NotNullByDefault
public enum ThemeBrightnessPreference {
    /// Retains the brightness resolved from the selected theme and its conditional overrides.
    THEME,

    /// Replaces theme brightness with the current operating-system brightness.
    SYSTEM,

    /// Always renders the light palette.
    LIGHT,

    /// Always renders the dark palette.
    DARK;

    /// Reconstructs the four-state preference from the legacy value and override-membership pair.
    ///
    /// The raw value is intentionally ignored when the appearance key is not overridden, because that value is
    /// retained only so a later explicit selection can restore the previous choice.
    ///
    /// @param overridden whether the brightness appearance key is overridden
    /// @param settingValue persisted `auto`, `system`, `light`, or `dark` value
    /// @return reconstructed four-state preference
    public static ThemeBrightnessPreference fromSetting(
            boolean overridden,
            @Nullable String settingValue) {
        if (!overridden) {
            return THEME;
        }
        if (settingValue == null) {
            return SYSTEM;
        }
        return switch (settingValue.trim().toLowerCase(Locale.ROOT)) {
            case "light" -> LIGHT;
            case "dark" -> DARK;
            case "auto", "system" -> SYSTEM;
            default -> SYSTEM;
        };
    }

    /// Resolves this preference against already resolved theme and operating-system values.
    ///
    /// @param themeBrightness brightness selected by the theme
    /// @param systemBrightness current operating-system brightness
    /// @return concrete brightness for rendering
    public ThemeBrightness resolve(
            ThemeBrightness themeBrightness,
            ThemeBrightness systemBrightness) {
        Objects.requireNonNull(themeBrightness, "themeBrightness");
        Objects.requireNonNull(systemBrightness, "systemBrightness");
        return switch (this) {
            case THEME -> themeBrightness;
            case SYSTEM -> systemBrightness;
            case LIGHT -> ThemeBrightness.LIGHT;
            case DARK -> ThemeBrightness.DARK;
        };
    }

    /// Returns the legacy value written when this preference is an explicit override.
    ///
    /// `THEME` has no value because it is represented by removing the brightness key from the override set.
    ///
    /// @return `auto`, `light`, `dark`, or `null` for theme inheritance
    public @Nullable String settingValue() {
        return switch (this) {
            case THEME -> null;
            case SYSTEM -> "auto";
            case LIGHT -> "light";
            case DARK -> "dark";
        };
    }
}
