// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
