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
package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;

/// User-selected handling for one conflicting Mod import source.
@NotNullByDefault
public enum ModImportConflictAction {
    /// Replaces every current Mod file with the same rename-stable local key.
    REPLACE,

    /// Leaves the current Mod unchanged and does not copy the new source.
    SKIP,

    /// Keeps both Mods by appending hyphens to the new file's base name until it is unique.
    KEEP
}
