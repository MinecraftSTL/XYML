// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.Task;

import java.util.Objects;

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

    /// Creates a fresh task for a retry using the progress recorded by an earlier attempt.
    ///
    /// Existing factories need only implement [#createTask()]. Factories that can safely resume after a durable
    /// checkpoint may override this method and skip the names in [RepairCheckpoint#completedSteps()]. The returned
    /// task must still be stopped and must perform its own validation, rollback, and resource cleanup.
    ///
    /// @param checkpoint immutable progress from the previous attempt
    /// @return independent repair task in the ready state
    default Task<?> createTask(RepairCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        return createTask();
    }
}
