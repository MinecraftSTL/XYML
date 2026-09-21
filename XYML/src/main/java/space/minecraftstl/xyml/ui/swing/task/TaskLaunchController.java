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
package space.minecraftstl.xyml.ui.swing.task;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.util.Objects;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Starts confirmed user-visible tasks and directs their progress to the task manager.
///
/// The executor is started before the confirmation surface is dismissed. A start failure therefore leaves the
/// original page or dialog open for its existing error handling, while navigation failures after a successful start
/// are logged without turning the committed task into a reported failure.
@NotNullByDefault
public final class TaskLaunchController {
    /// Shell command opening the task manager.
    private final Runnable openTaskManagerCommand;

    /// Creates a controller backed by one shell navigation command.
    ///
    /// @param openTaskManagerCommand command selecting the task-manager page
    public TaskLaunchController(Runnable openTaskManagerCommand) {
        this.openTaskManagerCommand = Objects.requireNonNull(
                openTaskManagerCommand,
                "openTaskManagerCommand");
    }

    /// Starts one user-visible task, dismisses its confirmation surface, and opens the task manager.
    ///
    /// @param executor unstarted task executor
    /// @param title stable user-facing task title
    /// @param dismissAction action closing or hiding the confirmation surface
    public void launch(TaskExecutor executor, String title, Runnable dismissAction) {
        EdtDispatcher.requireEventDispatchThread();
        TaskExecutor source = Objects.requireNonNull(executor, "executor");
        source.setTaskExecutionPresentation(
                Objects.requireNonNull(title, "title"),
                true);
        source.start();
        dismiss(dismissAction);
        openTaskManager();
    }

    /// Dismisses an already-registered task surface and opens the task manager.
    ///
    /// @param dismissAction action closing or hiding the confirmation surface
    public void openTaskManager(Runnable dismissAction) {
        EdtDispatcher.requireEventDispatchThread();
        dismiss(dismissAction);
        openTaskManager();
    }

    /// Runs one dismissal action without allowing its failure to hide an already-started task.
    private static void dismiss(Runnable dismissAction) {
        Runnable action = Objects.requireNonNull(dismissAction, "dismissAction");
        try {
            action.run();
        } catch (RuntimeException failure) {
            LOG.warning("Failed to dismiss a task confirmation surface", failure);
        }
    }

    /// Opens the task manager while preserving the already-committed task on navigation failure.
    private void openTaskManager() {
        try {
            openTaskManagerCommand.run();
        } catch (RuntimeException failure) {
            LOG.warning("Failed to open the task manager after starting a task", failure);
        }
    }
}
