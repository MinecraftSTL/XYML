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
package space.minecraftstl.xyml.ui.swing.log;

import org.jetbrains.annotations.NotNullByDefault;

import java.awt.Component;
import java.nio.file.Path;

/// Presents export outcomes without coupling the testable log panel to a concrete dialog implementation.
@NotNullByDefault
interface GameLogWindowNotifier {
    /// Presents a successful export and its destination.
    ///
    /// @param owner component owning the notification
    /// @param file exported file
    void exportSucceeded(Component owner, Path file);

    /// Presents an export failure with diagnostic context.
    ///
    /// @param owner component owning the notification
    /// @param operation localized operation name
    /// @param failure export failure
    void exportFailed(Component owner, String operation, Throwable failure);
}
