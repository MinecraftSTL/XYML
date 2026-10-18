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
