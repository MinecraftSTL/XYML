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

    /// Persists a complete theme-color source and palette-style configuration.
    ///
    /// @param themeColor complete replacement theme-color settings
    void setThemeColorAppearance(ThemeColorAppearanceSettings themeColor);

    /// Persists a complete background configuration in one model transition.
    ///
    /// @param background complete replacement background settings
    void setBackgroundAppearance(BackgroundAppearanceSettings background);
}
