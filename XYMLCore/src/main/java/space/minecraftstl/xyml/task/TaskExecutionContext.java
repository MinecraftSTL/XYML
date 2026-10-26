// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/// Starts executor-managed dynamic child tasks during one task invocation.
///
/// An [AsyncTaskExecutor] creates this context for one execution invocation and carries its owner token explicitly.
/// It never stores ownership on a reusable [Task] object and never infers it from a worker thread. Registered child
/// sources remain in the parent's structured lifetime even if their returned future views are discarded or cancelled.
/// Registration is valid only while [Task#execute(TaskExecutionContext)] or
/// [CompletableFutureTask#getFuture(TaskCompletableFuture)] is still open.
@NotNullByDefault
public interface TaskExecutionContext {

    /// Starts one child under the current invocation owner and returns an independently cancellable result view.
    ///
    /// The executor waits for the internal child source before releasing the parent lease. A normal [Task] that needs
    /// a child result to determine its own asynchronous result should use [CompletableFutureTask] instead of blocking
    /// on this future.
    ///
    /// @param task child task to execute under the current invocation owner
    /// @param <T> possibly nullable child result type
    /// @return independently cancellable child result view
    /// @throws IllegalStateException if the parent execution scope has already closed
    <T> CompletableFuture<@Nullable T> one(Task<T> task);

    /// Starts independent sibling children under the current invocation owner.
    ///
    /// The collection is snapshotted before any child is started. Each child receives a distinct owner token beneath
    /// the same parent, so conflicting siblings still serialize while non-conflicting siblings can run in parallel.
    ///
    /// @param tasks immutable-or-unmodified sibling task collection
    /// @return independently cancellable aggregate completion view
    /// @throws IllegalStateException if the parent execution scope has already closed
    CompletableFuture<@Nullable Void> all(@Unmodifiable Collection<? extends Task<?>> tasks);
}
