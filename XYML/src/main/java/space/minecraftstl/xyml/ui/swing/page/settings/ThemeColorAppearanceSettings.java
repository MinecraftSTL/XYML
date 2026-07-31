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
import space.minecraftstl.xyml.theme.ThemeColor;

import java.util.Objects;

/// Immutable custom launcher theme-color seed and selected-theme override state.
///
/// @param customColor persisted custom launcher accent seed
/// @param overridden whether the custom launcher color overrides the selected theme pack
@NotNullByDefault
public record ThemeColorAppearanceSettings(
        ThemeColor customColor,
        boolean overridden) {
    /// Creates the launcher defaults with the color inherited from the selected theme.
    ///
    /// @return default non-overridden theme-color settings
    public static ThemeColorAppearanceSettings defaults() {
        return new ThemeColorAppearanceSettings(
                ThemeColor.DEFAULT,
                false);
    }

    /// Rejects missing values before they reach theme resolution or persistence.
    public ThemeColorAppearanceSettings {
        Objects.requireNonNull(customColor, "customColor");
    }
}
