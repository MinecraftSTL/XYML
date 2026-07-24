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

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;

/// Package-private platform desktop boundary for resource-pack reveal and directory-open tests.
@NotNullByDefault
interface ResourcePackDesktopActions {
    /// Returns whether the platform desktop supports one action.
    ///
    /// @param action action queried
    /// @return whether the action is supported
    boolean isSupported(Desktop.Action action);

    /// Reveals the exact resource-pack path with the dedicated platform action.
    ///
    /// @param target resource-pack path to reveal
    /// @throws IOException if platform integration fails
    void browseFileDirectory(Path target) throws IOException;

    /// Opens one directory with the platform handler.
    ///
    /// @param directory directory to open
    /// @throws IOException if platform integration fails
    void open(Path directory) throws IOException;
}
