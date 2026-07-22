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
package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Stores top-level navigation state independently from Swing components.
@NotNullByDefault
public final class ShellNavigationState {
    /// The currently selected destination.
    private ShellPageId selectedPage;

    /// Creates navigation state at a caller-selected destination.
    ///
    /// @param initialPage the initial destination
    public ShellNavigationState(ShellPageId initialPage) {
        selectedPage = Objects.requireNonNull(initialPage);
    }

    /// Returns the currently selected destination.
    ///
    /// @return the selected destination
    public ShellPageId selectedPage() {
        return selectedPage;
    }

    /// Selects a destination and reports whether the state changed.
    ///
    /// @param page the destination to select
    /// @return `true` only when a different destination was selected
    public boolean select(ShellPageId page) {
        Objects.requireNonNull(page);
        if (selectedPage == page) {
            return false;
        }
        selectedPage = page;
        return true;
    }
}
