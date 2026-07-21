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
