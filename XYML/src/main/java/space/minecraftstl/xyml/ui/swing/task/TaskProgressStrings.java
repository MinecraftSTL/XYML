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
import space.minecraftstl.xyml.task.presentation.TaskStatus;

import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Localizable text used by [TaskProgressPanel] controls and lifecycle labels.
///
/// @param waitingStatus label for a queued or waiting task
/// @param runningStatus label for an active task
/// @param succeededStatus label for a successfully completed task
/// @param failedStatus label for a failed task
/// @param cancelledStatus label for a cancelled task
/// @param progressAccessibleName accessible name for the progress indicator
/// @param cancelAction label for the cancellation command
/// @param showDetailsAction label for expanding task details
/// @param hideDetailsAction label for collapsing task details
@NotNullByDefault
public record TaskProgressStrings(
        String waitingStatus,
        String runningStatus,
        String succeededStatus,
        String failedStatus,
        String cancelledStatus,
        String progressAccessibleName,
        String cancelAction,
        String showDetailsAction,
        String hideDetailsAction) {
    /// Validates and creates task progress text.
    public TaskProgressStrings {
        Objects.requireNonNull(waitingStatus, "waitingStatus");
        Objects.requireNonNull(runningStatus, "runningStatus");
        Objects.requireNonNull(succeededStatus, "succeededStatus");
        Objects.requireNonNull(failedStatus, "failedStatus");
        Objects.requireNonNull(cancelledStatus, "cancelledStatus");
        Objects.requireNonNull(progressAccessibleName, "progressAccessibleName");
        Objects.requireNonNull(cancelAction, "cancelAction");
        Objects.requireNonNull(showDetailsAction, "showDetailsAction");
        Objects.requireNonNull(hideDetailsAction, "hideDetailsAction");
    }

    /// Returns the built-in English text used when localization has not yet been connected.
    ///
    /// @return immutable English task progress text
    public static TaskProgressStrings english() {
        return new TaskProgressStrings(
                "Waiting",
                "Running",
                "Completed",
                "Failed",
                "Cancelled",
                "Task progress",
                "Cancel",
                "Show details",
                "Hide details");
    }

    /// Resolves generic task controls and lifecycle labels from the current launcher locale.
    ///
    /// @return immutable localized task-progress text
    public static TaskProgressStrings localized() {
        return new TaskProgressStrings(
                i18n("swing.task.status.waiting"),
                i18n("swing.task.status.running"),
                i18n("message.success"),
                i18n("message.failed"),
                i18n("message.cancelled"),
                i18n("swing.task.progress_name"),
                i18n("button.cancel"),
                i18n("swing.task.show_details"),
                i18n("swing.task.hide_details"));
    }

    /// Resolves the label corresponding to a lifecycle state.
    ///
    /// @param status the lifecycle state to label
    /// @return the localized label for that state
    public String statusText(TaskStatus status) {
        Objects.requireNonNull(status, "status");

        return switch (status) {
            case WAITING -> waitingStatus;
            case RUNNING -> runningStatus;
            case SUCCEEDED -> succeededStatus;
            case FAILED -> failedStatus;
            case CANCELLED -> cancelledStatus;
        };
    }
}
