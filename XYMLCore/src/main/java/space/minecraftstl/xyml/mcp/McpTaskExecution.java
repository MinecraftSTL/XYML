// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;

import java.awt.EventQueue;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/// Executes an unstarted MCP operation task through the shared asynchronous task arbiter.
///
/// The caller must already be on a background thread because this bridge waits for terminal completion. It retains the
/// [TaskExecutor] instead of using [Task#test()] so checked exceptions, runtime exceptions, errors, cancellation, and a
/// waiting-thread interruption can be distinguished without bypassing the task resource manager.
@NotNullByDefault
final class McpTaskExecution {
    /// Prevents construction of this execution bridge.
    private McpTaskExecution() {
    }

    /// Starts one root task, waits for termination, and returns its possibly absent result.
    ///
    /// A task-internal [InterruptedException] follows [TaskExecutor]'s established cancellation conversion. An
    /// interruption of the thread waiting in this method requests cancellation, preserves the interrupt flag, and is
    /// reported as a new [InterruptedException]. Every recorded exception or error is rethrown as the exact same object.
    ///
    /// @param task unstarted root task with a complete resource declaration
    /// @param <T> possibly nullable result type
    /// @return the task's possibly absent result
    /// @throws Exception when the task fails or the waiting thread is interrupted
    static <T> @Nullable T execute(Task<T> task) throws Exception {
        Task<T> checkedTask = Objects.requireNonNull(task, "task");
        if (EventQueue.isDispatchThread()) {
            throw new IllegalStateException("MCP task execution cannot block the AWT event dispatch thread");
        }
        TaskExecutor executor = checkedTask.executor();
        if (executor.test()) {
            return checkedTask.getResult();
        }

        if (Thread.currentThread().isInterrupted()) {
            executor.cancel();
            throw new InterruptedException("Interrupted while waiting for an MCP task");
        }
        @Nullable Throwable failure = executor.getFailure();
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (executor.isCancelled()) {
            throw new CancellationException("MCP task was cancelled");
        }
        throw new IllegalStateException("MCP task stopped without a terminal result or failure");
    }
}
