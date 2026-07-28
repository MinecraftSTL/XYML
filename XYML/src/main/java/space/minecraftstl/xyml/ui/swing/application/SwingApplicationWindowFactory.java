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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageFactory;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageId;

import javax.swing.JComponent;
import java.util.Map;

/// Creates the application window around a complete set of lazy page factories.
@FunctionalInterface
@NotNullByDefault
public interface SwingApplicationWindowFactory {
    /// Creates the native-window adapter.
    ///
    /// @param themeManager initialized by the concrete window before creating Swing components
    /// @param pageFactories immutable complete lazy page table
    /// @param presentation startup-provided text and transition policy
    /// @param animator shared animator owned by the composition
    /// @param models application models used by title-bar workflow controls
    /// @return application window adapter
    SwingApplicationWindow createWindow(
            SwingThemeManager themeManager,
            @Unmodifiable Map<ShellPageId, ShellPageFactory<? extends JComponent>> pageFactories,
            SwingApplicationPresentation presentation,
            SwingAnimator animator,
            SwingApplicationPageModels models);
}
