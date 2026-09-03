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
package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.Task;

/// Creates an independent stopped task for one explicit repair execution.
///
/// Implementations must not cache or return a task supplied to an earlier invocation. This keeps analysis and solution
/// inspection free of side effects and allows presentation or protocol layers to execute a selected repair once.
@FunctionalInterface
@NotNullByDefault
public interface RepairTaskFactory {
    /// Creates a fresh stopped task without starting it.
    ///
    /// @return independent repair task in the ready state
    Task<?> createTask();
}
