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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.setting.AnimationSpeedSettings;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;

import java.util.Objects;

/// Immutable raw appearance values supplied by a persistence adapter.
///
/// @param themeBrightnessValue persisted brightness identifier
/// @param cornerRadius current component radius
/// @param minimumCornerRadius minimum supported radius
/// @param maximumCornerRadius maximum supported radius
/// @param cornerRadiusStep supported radius increment
/// @param animationsDisabled whether non-essential motion is disabled
/// @param animationSpeed current launcher-supported discrete animation speed
/// @param themeColor complete persisted launcher theme-color values
/// @param background complete persisted launcher-background values
/// @param writable whether this store accepts changes
/// @param themeBrightnessOverridden whether brightness overrides the selected theme
@NotNullByDefault
public record StoredAppearanceSettings(
        String themeBrightnessValue,
        int cornerRadius,
        int minimumCornerRadius,
        int maximumCornerRadius,
        int cornerRadiusStep,
        boolean animationsDisabled,
        AnimationSpeedSettings animationSpeed,
        ThemeColorAppearanceSettings themeColor,
        BackgroundAppearanceSettings background,
        boolean writable,
        boolean themeBrightnessOverridden) {
    /// Creates raw appearance values for callers that do not customize animation speed.
    ///
    /// @param themeBrightnessValue persisted brightness identifier
    /// @param cornerRadius current component radius
    /// @param minimumCornerRadius minimum supported radius
    /// @param maximumCornerRadius maximum supported radius
    /// @param cornerRadiusStep supported radius increment
    /// @param animationsDisabled whether non-essential motion is disabled
    /// @param themeColor complete persisted launcher theme-color values
    /// @param background complete persisted launcher-background values
    /// @param writable whether this store accepts changes
    /// @param themeBrightnessOverridden whether brightness overrides the selected theme
    public StoredAppearanceSettings(
            String themeBrightnessValue,
            int cornerRadius,
            int minimumCornerRadius,
            int maximumCornerRadius,
            int cornerRadiusStep,
            boolean animationsDisabled,
            ThemeColorAppearanceSettings themeColor,
            BackgroundAppearanceSettings background,
            boolean writable,
            boolean themeBrightnessOverridden) {
        this(
                themeBrightnessValue,
                cornerRadius,
                minimumCornerRadius,
                maximumCornerRadius,
                cornerRadiusStep,
                animationsDisabled,
                AnimationSpeedSettings.defaults(),
                themeColor,
                background,
                writable,
                themeBrightnessOverridden);
    }

    /// Creates raw appearance values for callers that do not customize theme colors or animation speed.
    ///
    /// @param themeBrightnessValue persisted brightness identifier
    /// @param cornerRadius current component radius
    /// @param minimumCornerRadius minimum supported radius
    /// @param maximumCornerRadius maximum supported radius
    /// @param cornerRadiusStep supported radius increment
    /// @param animationsDisabled whether non-essential motion is disabled
    /// @param background complete persisted launcher-background values
    /// @param writable whether this store accepts changes
    /// @param themeBrightnessOverridden whether brightness overrides the selected theme
    public StoredAppearanceSettings(
            String themeBrightnessValue,
            int cornerRadius,
            int minimumCornerRadius,
            int maximumCornerRadius,
            int cornerRadiusStep,
            boolean animationsDisabled,
            BackgroundAppearanceSettings background,
            boolean writable,
            boolean themeBrightnessOverridden) {
        this(
                themeBrightnessValue,
                cornerRadius,
                minimumCornerRadius,
                maximumCornerRadius,
                cornerRadiusStep,
                animationsDisabled,
                ThemeColorAppearanceSettings.defaults(),
                background,
                writable,
                themeBrightnessOverridden);
    }

    /// Validates one raw settings snapshot.
    public StoredAppearanceSettings {
        Objects.requireNonNull(themeBrightnessValue, "themeBrightnessValue");
        Objects.requireNonNull(animationSpeed, "animationSpeed");
        Objects.requireNonNull(themeColor, "themeColor");
        Objects.requireNonNull(background, "background");
        if (minimumCornerRadius < 0) {
            throw new IllegalArgumentException("Minimum corner radius cannot be negative");
        }
        if (maximumCornerRadius < minimumCornerRadius) {
            throw new IllegalArgumentException("Maximum corner radius cannot precede minimum corner radius");
        }
        if (cornerRadius < minimumCornerRadius || cornerRadius > maximumCornerRadius) {
            throw new IllegalArgumentException("Corner radius must be inside the supported range");
        }
        if (cornerRadiusStep <= 0
                || (cornerRadius - minimumCornerRadius) % cornerRadiusStep != 0) {
            throw new IllegalArgumentException("Corner radius must align to a positive step");
        }
    }

    /// Reconstructs the four-state brightness preference from value and override membership.
    ///
    /// @return theme, system, light, or dark preference
    public ThemeBrightnessPreference brightnessPreference() {
        return ThemeBrightnessPreference.fromSetting(themeBrightnessOverridden, themeBrightnessValue);
    }
}
