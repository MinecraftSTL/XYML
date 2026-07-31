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
package space.minecraftstl.xyml.task.presentation;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;

/// Supplies read-only task presentation state independently of desktop UI toolkit types.
///
/// Implementations may publish from worker threads. Subscribers receive changes synchronously on the publishing
/// thread and must dispatch toolkit work themselves. A cancellation request must be idempotent because several
/// presentation surfaces may represent the same task.
@NotNullByDefault
public interface TaskPresentationModel {
    /// Returns the most recent immutable task state.
    ///
    /// @return the current snapshot
    TaskSnapshot snapshot();

    /// Registers a listener for future snapshot transitions.
    ///
    /// The registration does not emit an initial value; callers obtain it from [#snapshot()].
    ///
    /// @param listener the listener invoked on the publishing thread
    /// @return a subscription that removes this registration
    Subscription subscribe(ValueChangeListener<TaskSnapshot> listener);

    /// Requests cancellation if the current task state permits it.
    ///
    /// Repeated calls must have the same effect as one call.
    void requestCancellation();
}
