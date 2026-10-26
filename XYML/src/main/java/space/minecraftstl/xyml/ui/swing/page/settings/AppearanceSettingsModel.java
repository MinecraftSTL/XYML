// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;

/// Supplies and persists appearance settings without exposing JavaFX or Swing component state.
@NotNullByDefault
public interface AppearanceSettingsModel {
    /// Returns the latest immutable settings state.
    ///
    /// @return current appearance settings
    AppearanceSettingsSnapshot snapshot();

    /// Registers for future settings transitions on the publishing thread.
    ///
    /// @param listener the transition listener
    /// @return the independently cancellable registration
    Subscription subscribe(ValueChangeListener<AppearanceSettingsSnapshot> listener);

    /// Persists a four-state brightness preference.
    ///
    /// @param preference requested theme, system, light, or dark preference
    void setThemeBrightnessPreference(ThemeBrightnessPreference preference);

    /// Persists a supported component corner radius.
    ///
    /// @param cornerRadius the requested logical-pixel radius
    void setCornerRadius(int cornerRadius);

    /// Persists whether launcher animation is enabled.
    ///
    /// @param enabled whether animation should remain enabled
    void setAnimationsEnabled(boolean enabled);

    /// Persists a supported launcher animation speed.
    ///
    /// @param percentage requested speed percentage
    void setAnimationSpeedPercentage(int percentage);

    /// Persists a complete theme-color source and palette-style configuration.
    ///
    /// @param themeColor complete replacement theme-color settings
    void setThemeColorAppearance(ThemeColorAppearanceSettings themeColor);

    /// Persists a complete background configuration in one model transition.
    ///
    /// @param background complete replacement background settings
    void setBackgroundAppearance(BackgroundAppearanceSettings background);
}
