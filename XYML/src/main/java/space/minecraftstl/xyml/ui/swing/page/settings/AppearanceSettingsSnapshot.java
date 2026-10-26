// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.setting.AnimationSpeedSettings;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;

import java.util.Objects;

/// Immutable state rendered by the Swing appearance settings page.
///
/// The model supplies the complete radius range and step so the view never invents a default control budget.
///
/// @param brightnessPreference theme, system, light, or dark preference
/// @param cornerRadius current component corner radius in logical pixels
/// @param minimumCornerRadius smallest supported corner radius
/// @param maximumCornerRadius largest supported corner radius
/// @param cornerRadiusStep supported radius increment
/// @param animationsEnabled whether non-essential launcher animation is enabled
/// @param animationSpeed current launcher-supported discrete animation speed
/// @param themeColor complete launcher theme-color source and palette-style controls
/// @param background complete effective launcher-background setting controls
/// @param writable whether the current settings store accepts changes
@NotNullByDefault
public record AppearanceSettingsSnapshot(
        ThemeBrightnessPreference brightnessPreference,
        int cornerRadius,
        int minimumCornerRadius,
        int maximumCornerRadius,
        int cornerRadiusStep,
        boolean animationsEnabled,
        AnimationSpeedSettings animationSpeed,
        ThemeColorAppearanceSettings themeColor,
        BackgroundAppearanceSettings background,
        boolean writable) {

    /// Creates a snapshot for callers that do not customize animation speed.
    ///
    /// @param brightnessPreference theme, system, light, or dark preference
    /// @param cornerRadius current component corner radius
    /// @param minimumCornerRadius smallest supported corner radius
    /// @param maximumCornerRadius largest supported corner radius
    /// @param cornerRadiusStep supported radius increment
    /// @param animationsEnabled whether non-essential launcher animation is enabled
    /// @param themeColor complete launcher theme-color source and palette-style controls
    /// @param background complete launcher-background setting controls
    /// @param writable whether the current settings store accepts changes
    public AppearanceSettingsSnapshot(
            ThemeBrightnessPreference brightnessPreference,
            int cornerRadius,
            int minimumCornerRadius,
            int maximumCornerRadius,
            int cornerRadiusStep,
            boolean animationsEnabled,
            ThemeColorAppearanceSettings themeColor,
            BackgroundAppearanceSettings background,
            boolean writable) {
        this(
                brightnessPreference,
                cornerRadius,
                minimumCornerRadius,
                maximumCornerRadius,
                cornerRadiusStep,
                animationsEnabled,
                AnimationSpeedSettings.defaults(),
                themeColor,
                background,
                writable);
    }

    /// Creates a snapshot for callers that do not customize theme colors or animation speed.
    ///
    /// @param brightnessPreference theme, system, light, or dark preference
    /// @param cornerRadius current component corner radius
    /// @param minimumCornerRadius smallest supported corner radius
    /// @param maximumCornerRadius largest supported corner radius
    /// @param cornerRadiusStep supported radius increment
    /// @param animationsEnabled whether non-essential launcher animation is enabled
    /// @param background complete launcher-background setting controls
    /// @param writable whether the current settings store accepts changes
    public AppearanceSettingsSnapshot(
            ThemeBrightnessPreference brightnessPreference,
            int cornerRadius,
            int minimumCornerRadius,
            int maximumCornerRadius,
            int cornerRadiusStep,
            boolean animationsEnabled,
            BackgroundAppearanceSettings background,
            boolean writable) {
        this(
                brightnessPreference,
                cornerRadius,
                minimumCornerRadius,
                maximumCornerRadius,
                cornerRadiusStep,
                animationsEnabled,
                ThemeColorAppearanceSettings.defaults(),
                background,
                writable);
    }

    /// Validates one appearance settings snapshot.
    public AppearanceSettingsSnapshot {
        Objects.requireNonNull(brightnessPreference, "brightnessPreference");
        Objects.requireNonNull(animationSpeed, "animationSpeed");
        Objects.requireNonNull(themeColor, "themeColor");
        Objects.requireNonNull(background, "background");
        if (minimumCornerRadius < 0) {
            throw new IllegalArgumentException("minimumCornerRadius must not be negative");
        }
        if (maximumCornerRadius < minimumCornerRadius) {
            throw new IllegalArgumentException("maximumCornerRadius must not precede minimumCornerRadius");
        }
        if (cornerRadius < minimumCornerRadius || cornerRadius > maximumCornerRadius) {
            throw new IllegalArgumentException("cornerRadius must be within the supported range");
        }
        if (cornerRadiusStep <= 0) {
            throw new IllegalArgumentException("cornerRadiusStep must be positive");
        }
        if ((cornerRadius - minimumCornerRadius) % cornerRadiusStep != 0) {
            throw new IllegalArgumentException("cornerRadius must align to cornerRadiusStep");
        }
    }
}
