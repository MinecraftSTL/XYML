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
        BackgroundAppearanceSettings background,
        boolean writable) {

    /// Validates one appearance settings snapshot.
    public AppearanceSettingsSnapshot {
        Objects.requireNonNull(brightnessPreference, "brightnessPreference");
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
