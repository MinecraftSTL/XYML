// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.concurrent.Executor;

/// Dispatches work to the active desktop UI toolkit without exposing toolkit-specific types.
///
/// Implementations define the concrete UI thread and queue. Core services can depend on this contract while Swing,
/// tests, or another frontend supplies the implementation.
@NotNullByDefault
public interface UiDispatcher extends Executor {
    /// Returns whether the calling thread is the dispatcher's UI thread.
    boolean isDispatchThread();

    /// Schedules an operation for execution on the UI thread.
    ///
    /// The implementation must preserve submission order and must reject a null operation.
    void dispatch(Runnable operation);

    /// Delegates executor submissions to [#dispatch(Runnable)].
    @Override
    default void execute(Runnable command) {
        dispatch(command);
    }

    /// Runs an operation immediately when already on the UI thread, or dispatches it otherwise.
    default void dispatchOrRun(Runnable operation) {
        Objects.requireNonNull(operation, "operation");

        if (isDispatchThread()) {
            operation.run();
        } else {
            dispatch(operation);
        }
    }
}
