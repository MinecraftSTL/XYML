// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
