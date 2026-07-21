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
package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;

/// Reads the operating-system appearance used to resolve the system theme mode.
///
/// Implementations must be fast and non-blocking because the theme manager calls them on the Swing event dispatch thread.
@FunctionalInterface
@NotNullByDefault
public interface SystemThemeDetector {
    /// Returns a deterministic light-mode detector for platforms without a reliable native appearance signal.
    ///
    /// @return a detector that always reports light appearance
    static SystemThemeDetector lightFallback() {
        return () -> false;
    }

    /// Returns whether the operating system currently requests a dark appearance.
    ///
    /// @return `true` for dark appearance and `false` for light appearance
    boolean isDarkTheme();
}
