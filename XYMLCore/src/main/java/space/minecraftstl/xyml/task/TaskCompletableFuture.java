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
package space.minecraftstl.xyml.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/// Starts executor-managed child tasks inside one completable-future task invocation.
///
/// Every child started through this scope remains part of its parent's structured lifetime even when the caller
/// discards or cancels the returned future view. Callers must register children before the future returned by
/// [CompletableFutureTask#getFuture(TaskCompletableFuture)] reaches a terminal state. Child failures affect the parent
/// outcome only when that returned view is composed into the parent future, preserving the established propagation
/// contract while preventing early resource release.
@NotNullByDefault
public interface TaskCompletableFuture {

    /// Starts one child task and returns an independently cancellable view of its result.
    ///
    /// @param task child task to execute under the current invocation owner
    /// @param <T> possibly nullable child result type
    /// @return future view whose cancellation does not cancel internal resource cleanup
    /// @throws IllegalStateException if the parent scope has already closed
    <T> CompletableFuture<@Nullable T> one(Task<T> task);

    /// Starts an immutable snapshot of child tasks and returns an independently cancellable aggregate view.
    ///
    /// @param tasks child tasks to execute as independent siblings under the current invocation owner
    /// @return future view completed after every child reaches a terminal state
    /// @throws IllegalStateException if the parent scope has already closed
    CompletableFuture<@Nullable Void> all(Collection<Task<?>> tasks);
}
