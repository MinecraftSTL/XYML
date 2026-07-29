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

    /// Persists every background field and its selected-theme override membership atomically.
    ///
    /// @param background complete replacement background settings
    void setBackgroundAppearance(BackgroundAppearanceSettings background);
}
