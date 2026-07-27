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
import space.minecraftstl.xyml.ui.swing.ThemeMode;

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

    /// Persists a new light, dark, or system theme preference.
    ///
    /// @param themeMode the requested theme mode
    void setThemeMode(ThemeMode themeMode);

    /// Persists a four-state brightness preference.
    ///
    /// Compatibility implementations support the three explicit historical modes. Implementations backed by
    /// appearance override membership should override this method to support theme inheritance.
    ///
    /// @param preference requested theme, system, light, or dark preference
    default void setThemeBrightnessPreference(ThemeBrightnessPreference preference) {
        ThemeMode mode = switch (java.util.Objects.requireNonNull(preference, "preference")) {
            case THEME -> throw new UnsupportedOperationException(
                    "This appearance model cannot inherit selected-theme brightness");
            case SYSTEM -> ThemeMode.SYSTEM;
            case LIGHT -> ThemeMode.LIGHT;
            case DARK -> ThemeMode.DARK;
        };
        setThemeMode(mode);
    }

    /// Persists a supported component corner radius.
    ///
    /// @param cornerRadius the requested logical-pixel radius
    void setCornerRadius(int cornerRadius);

    /// Persists whether launcher animation is enabled.
    ///
    /// @param enabled whether animation should remain enabled
    void setAnimationsEnabled(boolean enabled);
}
