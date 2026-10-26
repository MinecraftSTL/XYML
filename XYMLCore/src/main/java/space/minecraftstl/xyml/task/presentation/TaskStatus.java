// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
