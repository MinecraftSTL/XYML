// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.task;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.util.Objects;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Starts confirmed user-visible tasks with an explicit task-manager navigation policy.
///
/// Each launch method starts the executor before dismissing the confirmation surface. A start failure therefore
/// leaves the original page or dialog open for its existing error handling, while navigation failures after a successful start
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
        start(executor, title, dismissAction);
        openTaskManager();
    }

    /// Starts one user-visible task and dismisses its confirmation surface without navigating.
    ///
    /// @param executor unstarted task executor
    /// @param title stable user-facing task title
    /// @param dismissAction action closing or hiding the confirmation surface
    public void launchWithoutNavigation(TaskExecutor executor, String title, Runnable dismissAction) {
        start(executor, title, dismissAction);
    }

    /// Registers and starts one user-visible task before dismissing its confirmation surface.
    ///
    /// @param executor unstarted task executor
    /// @param title stable user-facing task title
    /// @param dismissAction action closing or hiding the confirmation surface
    private static void start(TaskExecutor executor, String title, Runnable dismissAction) {
        EdtDispatcher.requireEventDispatchThread();
        TaskExecutor source = Objects.requireNonNull(executor, "executor");
        source.setTaskExecutionPresentation(
                Objects.requireNonNull(title, "title"),
                true);
        source.start();
        dismiss(dismissAction);
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
