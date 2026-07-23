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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects which game-version kinds remain visible in the local catalog snapshot.
@NotNullByDefault
public enum GameVersionFilter {
    /// Includes every game-version kind.
    ALL,

    /// Includes only normal releases.
    RELEASE,

    /// Includes only snapshots and pre-releases.
    SNAPSHOT,

    /// Includes only April Fools' Day versions.
    APRIL_FOOLS,

    /// Includes only historical versions.
    OLD;

    /// Returns whether one kind passes this filter.
    ///
    /// @param kind version kind to test
    /// @return whether the kind is visible
    public boolean includes(GameVersionKind kind) {
        return this == ALL || name().equals(kind.name());
    }
}
