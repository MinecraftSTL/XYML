// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.launch;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes the preparation lifecycle of one launch session without implying process liveness.
@NotNullByDefault
public enum LaunchStatus {
    /// The launch task is being created or is preparing the managed process.
    PREPARING,

    /// Preparation produced a managed process; the process may subsequently exit independently.
    PROCESS_CREATED,

    /// Preparation terminated because of a non-cancellation failure.
    FAILED,

    /// Preparation terminated after cancellation, including when a concurrently produced process was force-stopped.
    CANCELLED;

    /// Returns whether no further preparation-state transition is possible.
    ///
    /// @return true for every state except [#PREPARING]
    public boolean isTerminal() {
        return this != PREPARING;
    }
}
