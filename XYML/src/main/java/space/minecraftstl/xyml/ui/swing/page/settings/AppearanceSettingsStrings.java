// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localizable visible and accessible text for the appearance settings page.
///
/// @param pageTitle page heading
/// @param themeModeLabel theme-mode field label
/// @param followThemeLabel selected-theme brightness option
/// @param systemThemeLabel system-following theme option
/// @param lightThemeLabel light theme option
/// @param darkThemeLabel dark theme option
/// @param cornerRadiusLabel corner-radius field label
/// @param animationsLabel animation toggle label
/// @param animationSpeedLabel animation-speed field label
/// @param background complete launcher-background controls
@NotNullByDefault
public record AppearanceSettingsStrings(
        String pageTitle,
        String themeModeLabel,
        String followThemeLabel,
        String systemThemeLabel,
        String lightThemeLabel,
        String darkThemeLabel,
        String cornerRadiusLabel,
        String animationsLabel,
        String animationSpeedLabel,
        AppearanceBackgroundStrings background) {
    /// Validates localized settings text.
    public AppearanceSettingsStrings {
        Objects.requireNonNull(pageTitle, "pageTitle");
        Objects.requireNonNull(themeModeLabel, "themeModeLabel");
        Objects.requireNonNull(followThemeLabel, "followThemeLabel");
        Objects.requireNonNull(systemThemeLabel, "systemThemeLabel");
        Objects.requireNonNull(lightThemeLabel, "lightThemeLabel");
        Objects.requireNonNull(darkThemeLabel, "darkThemeLabel");
        Objects.requireNonNull(cornerRadiusLabel, "cornerRadiusLabel");
        Objects.requireNonNull(animationsLabel, "animationsLabel");
        Objects.requireNonNull(animationSpeedLabel, "animationSpeedLabel");
        Objects.requireNonNull(background, "background");
    }
}
