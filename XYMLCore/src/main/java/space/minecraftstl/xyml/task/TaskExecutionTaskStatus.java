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
package space.minecraftstl.xyml.task;

import org.jetbrains.annotations.NotNullByDefault;

/// Lifecycle state of one actual task inside a top-level execution.
@NotNullByDefault
public enum TaskExecutionTaskStatus {
    /// The task is waiting for its resource lease or prerequisites.
    WAITING,

    /// The task body is running.
    RUNNING,

    /// The task completed successfully.
    SUCCEEDED,

    /// The task failed.
    FAILED,

    /// The task was cancelled before successful completion.
    CANCELLED
}
