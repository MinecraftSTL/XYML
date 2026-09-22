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

/// Terminal and non-terminal states exposed for one top-level task execution.
@NotNullByDefault
public enum TaskExecutionStatus {
    /// The top-level task is queued or waiting for a resource lease.
    WAITING,

    /// At least one task in the execution is running.
    RUNNING,

    /// Cancellation was requested and the execution has not stopped yet.
    CANCELLING,

    /// The complete top-level task graph succeeded.
    SUCCEEDED,

    /// The complete top-level task graph failed.
    FAILED,

    /// The complete top-level task graph was cancelled.
    CANCELLED;

    /// Returns whether this state is terminal.
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
