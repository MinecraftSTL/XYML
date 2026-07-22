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

import java.util.function.Consumer;

/// Builds page models after the composition has established its shell-navigation reference.
@FunctionalInterface
@NotNullByDefault
public interface SwingApplicationPageModelFactory {
    /// Builds every page model and its ordered owned-resource lifecycle.
    ///
    /// The navigation consumer remains valid for the composition lifetime and delegates to the
    /// hosted shell once the window factory has returned.
    ///
    /// @param navigateCommand command that selects a stable shell destination
    /// @return page models and ordered owned resources
    SwingApplicationPageModels createModels(Consumer<ShellPageId> navigateCommand);
}
