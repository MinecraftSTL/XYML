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
package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/// One immutable directory or Litematic row returned for a viewport range.
@NotNullByDefault
public sealed interface SchematicBrowserItem permits SchematicDirectoryItem, SchematicFileItem {
    /// Returns the exact path represented by this row.
    ///
    /// @return row path
    Path path();

    /// Returns the source file name used for deterministic ordering and fallback display.
    ///
    /// @return source file name
    String fileName();
}
