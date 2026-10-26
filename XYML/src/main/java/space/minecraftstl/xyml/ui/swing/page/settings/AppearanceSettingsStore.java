// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;

/// Persists raw appearance values without exposing a UI toolkit property type.
@NotNullByDefault
public interface AppearanceSettingsStore {
    /// Returns the latest raw persisted values.
    ///
    /// @return current store snapshot
    StoredAppearanceSettings snapshot();

    /// Registers for future raw setting transitions on the publishing thread.
    ///
    /// @param listener raw snapshot transition listener
    /// @return independently cancellable registration
    Subscription subscribe(ValueChangeListener<StoredAppearanceSettings> listener);

    /// Persists a four-state brightness preference.
    ///
    /// @param preference requested brightness preference
    void setThemeBrightnessPreference(ThemeBrightnessPreference preference);

    /// Persists a validated corner radius.
    ///
    /// @param cornerRadius logical-pixel radius
    void setCornerRadius(int cornerRadius);

    /// Persists whether non-essential motion is disabled.
    ///
    /// @param disabled whether animation is disabled
    void setAnimationsDisabled(boolean disabled);

    /// Persists a validated launcher animation speed.
    ///
    /// @param percentage speed percentage
    void setAnimationSpeedPercentage(int percentage);

    /// Persists theme-color source, custom seed, style, and override membership atomically.
    ///
    /// @param themeColor complete replacement theme-color settings
    void setThemeColorAppearance(ThemeColorAppearanceSettings themeColor);

    /// Persists every background field and its selected-theme override membership atomically.
    ///
    /// @param background complete replacement background settings
    void setBackgroundAppearance(BackgroundAppearanceSettings background);
}
