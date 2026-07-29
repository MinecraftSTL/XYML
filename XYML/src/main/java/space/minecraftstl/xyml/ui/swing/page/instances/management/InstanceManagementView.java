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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;

import javax.swing.JComponent;

/// One owned, dynamically hosted management view for a stable game-instance identifier.
///
/// Implementations are created, queried, hosted, and closed exclusively on the Swing EDT.
@NotNullByDefault
public interface InstanceManagementView extends AutoCloseable {
    /// Returns the stable repository identifier captured by this view.
    ///
    /// @return stable instance identifier
    GameInstanceID instanceId();

    /// Returns the root component hosted inside the instances page.
    ///
    /// @return root management component
    JComponent component();

    /// Releases every resource owned by this dynamic view on the EDT.
    @Override
    void close();
}
