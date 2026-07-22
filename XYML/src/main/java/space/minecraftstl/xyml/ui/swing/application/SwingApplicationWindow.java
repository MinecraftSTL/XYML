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
package space.minecraftstl.xyml.ui.swing.application;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageId;

/// Abstracts the native Swing window so composition lifecycle and factories can be tested headlessly.
@NotNullByDefault
public interface SwingApplicationWindow extends AutoCloseable {
    /// Registers the composition cleanup callback invoked after native window disposal.
    ///
    /// @param closedHandler callback to invoke at most once after the window closes
    void setClosedHandler(Runnable closedHandler);

    /// Shows the native window.
    void open();

    /// Navigates the hosted shell to one stable destination.
    ///
    /// @param page destination selected by a page command
    void navigateTo(ShellPageId page);

    /// Disposes the native window idempotently.
    @Override
    void close();
}
