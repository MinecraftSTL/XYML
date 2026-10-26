// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

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
public interface TaskCompletableFuture extends TaskExecutionContext {

    /// Starts one child task and returns an independently cancellable view of its result.
    ///
    /// @param task child task to execute under the current invocation owner
    /// @param <T> possibly nullable child result type
    /// @return future view whose cancellation does not cancel internal resource cleanup
    /// @throws IllegalStateException if the parent scope has already closed
    @Override
    <T> CompletableFuture<@Nullable T> one(Task<T> task);

    /// Starts an immutable snapshot of child tasks and returns an independently cancellable aggregate view.
    ///
    /// @param tasks child tasks to execute as independent siblings under the current invocation owner
    /// @return future view completed after every child reaches a terminal state
    /// @throws IllegalStateException if the parent scope has already closed
    @Override
    CompletableFuture<@Nullable Void> all(@Unmodifiable Collection<? extends Task<?>> tasks);
}
