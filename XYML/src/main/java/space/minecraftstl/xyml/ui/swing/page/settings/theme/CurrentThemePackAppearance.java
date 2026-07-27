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
package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.theme.ResolvedTheme;
import space.minecraftstl.xyml.ui.swing.SwingWindowAppearanceRequest;

import java.util.Objects;

/// One consistent renderer-ready appearance captured before a current-theme export starts.
///
/// @param theme concrete color, brightness, palette style, and contrast
/// @param window concrete background source and opacity
@NotNullByDefault
public record CurrentThemePackAppearance(
        ResolvedTheme theme,
        SwingWindowAppearanceRequest window) {
    /// Rejects incomplete appearance captures.
    public CurrentThemePackAppearance {
        Objects.requireNonNull(theme, "theme");
        Objects.requireNonNull(window, "window");
    }
}
