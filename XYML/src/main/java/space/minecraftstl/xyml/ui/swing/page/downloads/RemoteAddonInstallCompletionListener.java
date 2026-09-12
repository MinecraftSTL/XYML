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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;

import java.util.Objects;
import java.util.function.BiConsumer;

/// Forwards one installation executor's terminal transition to its owning catalog panel.
///
/// The identity check prevents a stale executor from changing the state of a newer installation
/// that reused the same panel.
@NotNullByDefault
final class RemoteAddonInstallCompletionListener extends TaskListener {
    /// Executor represented by this listener.
    private final TaskExecutor sourceExecutor;

    /// Callback owned by the catalog panel.
    private final BiConsumer<TaskExecutor, Boolean> completion;

    /// Creates a terminal listener for exactly one active task executor.
    ///
    /// @param sourceExecutor task executor whose lifecycle should update the panel
    /// @param completion callback receiving the matching executor and outcome
    RemoteAddonInstallCompletionListener(
            TaskExecutor sourceExecutor,
            BiConsumer<TaskExecutor, Boolean> completion) {
        this.sourceExecutor = Objects.requireNonNull(sourceExecutor, "sourceExecutor");
        this.completion = Objects.requireNonNull(completion, "completion");
    }

    /// Publishes terminal status only for the exact retained executor.
    ///
    /// @param succeeded whether the full task graph completed successfully
    /// @param executor executor reporting the terminal transition
    @Override
    public void onStop(boolean succeeded, TaskExecutor executor) {
        if (executor == sourceExecutor) {
            completion.accept(sourceExecutor, succeeded);
        }
    }
}
