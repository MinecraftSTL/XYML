// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.theme;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

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

    /// Reconstructs the four-state preference from the persisted value and override-membership pair.
    ///
    /// The raw value is intentionally ignored when the appearance key is not overridden, because that value is
    /// retained only so a later explicit selection can restore the previous choice.
    ///
    /// @param overridden whether the brightness appearance key is overridden
    /// @param settingValue persisted `system`, `light`, or `dark` value
    /// @return reconstructed four-state preference
    public static ThemeBrightnessPreference fromSetting(
            boolean overridden,
            @Nullable String settingValue) {
        if (!overridden) {
            return THEME;
        }
        if (settingValue == null) {
            throw new IllegalArgumentException("Overridden theme brightness requires a value");
        }
        return switch (settingValue) {
            case "system" -> SYSTEM;
            case "light" -> LIGHT;
            case "dark" -> DARK;
            default -> throw new IllegalArgumentException(
                    "Unsupported theme brightness value: " + settingValue);
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

    /// Returns the canonical value written when this preference is an explicit override.
    ///
    /// `THEME` has no value because it is represented by removing the brightness key from the override set.
    ///
    /// @return `system`, `light`, `dark`, or `null` for theme inheritance
    public @Nullable String settingValue() {
        return switch (this) {
            case THEME -> null;
            case SYSTEM -> "system";
            case LIGHT -> "light";
            case DARK -> "dark";
        };
    }
}
