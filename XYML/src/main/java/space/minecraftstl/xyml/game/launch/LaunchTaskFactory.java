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
package space.minecraftstl.xyml.game.launch;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.platform.ManagedProcess;

/// Creates the task that prepares one immutable launch request and returns its managed process.
///
/// Implementations should perform substantial preparation inside the returned task so cancellation and task
/// presentation remain available through the standard executor.
@FunctionalInterface
@NotNullByDefault
public interface LaunchTaskFactory {
    /// Creates one not-yet-started task for the supplied captured request.
    ///
    /// @param request immutable stable launch identifiers
    /// @return task whose successful result is the created managed process
    Task<ManagedProcess> create(LaunchRequest request);
}
