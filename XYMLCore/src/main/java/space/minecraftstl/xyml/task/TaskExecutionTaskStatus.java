// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
