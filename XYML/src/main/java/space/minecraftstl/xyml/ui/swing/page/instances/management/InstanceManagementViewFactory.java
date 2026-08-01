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

/// Creates one internally hosted management view on the Swing EDT.
@FunctionalInterface
@NotNullByDefault
public interface InstanceManagementViewFactory {
    /// Creates a view for an exact stable instance identifier.
    ///
    /// The returned view transfers to the coordinator. The return command may be called from any
    /// thread and requests disposal of the dynamic view before showing the instances list again.
    ///
    /// @param instanceId stable repository instance identifier
    /// @param returnCommand command returning to the instances list
    /// @return newly owned management view
    InstanceManagementView create(GameInstanceID instanceId, Runnable returnCommand);
}
