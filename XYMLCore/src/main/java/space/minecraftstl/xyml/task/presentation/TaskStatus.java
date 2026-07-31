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
package space.minecraftstl.xyml.task.presentation;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes the presentation lifecycle of a background task without exposing an execution toolkit.
@NotNullByDefault
public enum TaskStatus {
    /// The task is queued or waiting for a prerequisite.
    WAITING,

    /// The task is actively performing work.
    RUNNING,

    /// The task completed normally.
    SUCCEEDED,

    /// The task stopped because an operation failed.
    FAILED,

    /// The task stopped after cancellation was accepted.
    CANCELLED;

    /// Returns whether no further task work is expected after this status.
    ///
    /// @return `true` for successful, failed, and cancelled states
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
