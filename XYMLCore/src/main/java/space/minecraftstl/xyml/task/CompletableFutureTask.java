/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
import space.minecraftstl.xyml.util.Lang;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/// A task whose completion is represented by a [CompletableFuture].
///
/// The executor keeps this task's resource lease until the future returned by [#getFuture(TaskCompletableFuture)]
/// reaches a terminal state and all executor-managed completion handling has finished. Implementations should create
/// child tasks through the supplied [TaskCompletableFuture] rather than bypassing the executor.
@NotNullByDefault
public abstract class CompletableFutureTask<T> extends Task<T> {

    /// Retains the regular task entry point for compatibility with synchronous callers.
    @Override
    public void execute() throws Exception {
    }

    /// Creates the asynchronous result for this invocation.
    ///
    /// @param executor invocation-scoped context for starting child tasks
    /// @return future representing the complete asynchronous operation
    /// @throws Exception if creating the future fails synchronously
    public abstract CompletableFuture<T> getFuture(TaskCompletableFuture executor);

    /// Exception used by [#breakable(CompletableFuture)] to stop a best-effort continuation without failing it.
    public static class CustomException extends RuntimeException {}

    /// Converts a future into a completion-only future while preserving ordinary failures.
    ///
    /// A [CustomException] is treated as an intentional early completion. Other failures are wrapped in a
    /// [CompletionException], preserving the historical behavior of this helper.
    ///
    /// @param future source future
    /// @return completion-only future
    protected static CompletableFuture<Void> breakable(CompletableFuture<?> future) {
        return future.thenApplyAsync(unused1 -> (Void) null).exceptionally(throwable -> {
            if (Lang.resolveException(throwable) instanceof CustomException) return null;
            else throw new CompletionException(throwable);
        });
    }

}
