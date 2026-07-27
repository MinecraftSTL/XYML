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
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;

import java.util.Objects;

/// Immutable raw appearance values supplied by a persistence adapter.
///
/// @param themeModeValue persisted brightness identifier
/// @param cornerRadius current component radius
/// @param minimumCornerRadius minimum supported radius
/// @param maximumCornerRadius maximum supported radius
/// @param cornerRadiusStep supported radius increment
/// @param animationsDisabled whether non-essential motion is disabled
/// @param writable whether this store accepts changes
/// @param themeBrightnessOverridden whether brightness overrides the selected theme
@NotNullByDefault
public record StoredAppearanceSettings(
        String themeModeValue,
        int cornerRadius,
        int minimumCornerRadius,
        int maximumCornerRadius,
        int cornerRadiusStep,
        boolean animationsDisabled,
        boolean writable,
        boolean themeBrightnessOverridden) {
    /// Creates a compatibility snapshot whose legacy brightness value is explicitly overridden.
    ///
    /// @param themeModeValue persisted brightness identifier
    /// @param cornerRadius current component radius
    /// @param minimumCornerRadius minimum supported radius
    /// @param maximumCornerRadius maximum supported radius
    /// @param cornerRadiusStep supported radius increment
    /// @param animationsDisabled whether non-essential motion is disabled
    /// @param writable whether this store accepts changes
    public StoredAppearanceSettings(
            String themeModeValue,
            int cornerRadius,
            int minimumCornerRadius,
            int maximumCornerRadius,
            int cornerRadiusStep,
            boolean animationsDisabled,
            boolean writable) {
        this(
                themeModeValue,
                cornerRadius,
                minimumCornerRadius,
                maximumCornerRadius,
                cornerRadiusStep,
                animationsDisabled,
                writable,
                true);
    }

    /// Validates one raw settings snapshot.
    public StoredAppearanceSettings {
        Objects.requireNonNull(themeModeValue, "themeModeValue");
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
        return ThemeBrightnessPreference.fromSetting(themeBrightnessOverridden, themeModeValue);
    }
}
