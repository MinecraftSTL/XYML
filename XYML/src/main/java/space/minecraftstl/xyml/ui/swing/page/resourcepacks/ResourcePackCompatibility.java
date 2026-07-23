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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;

/// Toolkit-neutral compatibility state for one installed resource pack.
@NotNullByDefault
public enum ResourcePackCompatibility {
    /// The pack declares support for the managed Minecraft version.
    COMPATIBLE,

    /// The pack targets a newer Minecraft resource format.
    TOO_NEW,

    /// The pack targets an older Minecraft resource format.
    TOO_OLD,

    /// The pack metadata contains an invalid resource-format declaration.
    INVALID,

    /// The pack has no readable `pack.mcmeta` declaration.
    MISSING_PACK_META,

    /// The managed game instance has no readable resource-format metadata.
    MISSING_GAME_META
}
