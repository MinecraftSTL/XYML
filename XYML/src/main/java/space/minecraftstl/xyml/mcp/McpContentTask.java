// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.Task;

import java.util.concurrent.CancellationException;

/// I/O task exposing its executor-owned cancellation boundary to a multi-step deletion operation.
/// @param <T> immutable operation result
@NotNullByDefault
final class McpContentTask<T> extends Task<T> {
    /// Operation receiving the owning task's cancellation checkpoint.
    private final Operation<T> operation;

    /// Creates an unstarted task without acquiring resources.
    /// @param operation operation body
    McpContentTask(Operation<T> operation) {
        this.operation = operation;
    }

    /// Runs the operation with cancellation checks before and between destructive steps.
    @Override
    public void execute() throws Exception {
        checkpoint();
        setResult(operation.run(this::checkpoint));
    }

    /// Rejects side effects once this task or its worker has been cancelled.
    private void checkpoint() {
        if (isCancelled()) {
            throw new CancellationException("MCP content operation cancelled");
        }
    }

    /// Operation aware of its owning executor's cancellation state.
    /// @param <T> result type
    @NotNullByDefault
    @FunctionalInterface
    interface Operation<T> {
        /// Runs the operation.
        /// @param checkpoint cancellation check to invoke before each side effect
        /// @return operation result
        /// @throws Exception when execution fails
        T run(Runnable checkpoint) throws Exception;
    }
}
