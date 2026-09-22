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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies the shared confirmed-task submission and navigation order.
@NotNullByDefault
public final class TaskLaunchControllerTest {
    /// Start, dismissal, and navigation occur in their contractual order.
    @Test
    public void launchesAndNavigatesInOrder() {
        EdtDispatcher.executeAndWait(() -> {
            List<String> events = new ArrayList<>();
            TaskLaunchController controller = new TaskLaunchController(() -> events.add("navigate"));
            TaskExecutor executor = new RecordingExecutor(events, false);

            controller.launch(executor, "Install fixture", () -> events.add("dismiss"));

            assertEquals(List.of("start", "dismiss", "navigate"), events);
        });
    }

    /// A local launch starts and dismisses without opening the task manager.
    @Test
    public void launchesWithoutNavigationInOrder() {
        EdtDispatcher.executeAndWait(() -> {
            List<String> events = new ArrayList<>();
            TaskLaunchController controller = new TaskLaunchController(() -> events.add("navigate"));
            TaskExecutor executor = new RecordingExecutor(events, false);

            controller.launchWithoutNavigation(executor, "Local fixture", () -> events.add("dismiss"));

            assertEquals(List.of("start", "dismiss"), events);
        });
    }

    /// A failed local launch preserves the confirmation surface and never navigates.
    @Test
    public void launchWithoutNavigationStartFailureDoesNotDismiss() {
        EdtDispatcher.executeAndWait(() -> {
            List<String> events = new ArrayList<>();
            TaskLaunchController controller = new TaskLaunchController(() -> events.add("navigate"));
            TaskExecutor executor = new RecordingExecutor(events, true);

            assertThrows(
                    IllegalStateException.class,
                    () -> controller.launchWithoutNavigation(
                            executor,
                            "Local fixture",
                            () -> events.add("dismiss")));
            assertEquals(List.of("start"), events);
        });
    }

    /// A start failure preserves the confirmation surface and never navigates.
    @Test
    public void startFailureDoesNotDismissOrNavigate() {
        EdtDispatcher.executeAndWait(() -> {
            List<String> events = new ArrayList<>();
            TaskLaunchController controller = new TaskLaunchController(() -> events.add("navigate"));
            TaskExecutor executor = new RecordingExecutor(events, true);

            assertThrows(IllegalStateException.class,
                    () -> controller.launch(executor, "Install fixture", () -> events.add("dismiss")));
            assertEquals(List.of("start"), events);
        });
    }

    /// A successful start still reaches the task manager when dismissal fails.
    @Test
    public void dismissalFailureDoesNotPreventNavigation() {
        EdtDispatcher.executeAndWait(() -> {
            List<String> events = new ArrayList<>();
            TaskLaunchController controller = new TaskLaunchController(() -> events.add("navigate"));
            TaskExecutor executor = new RecordingExecutor(events, false);

            controller.launch(executor, "Install fixture", () -> {
                events.add("dismiss");
                throw new IllegalStateException("dismiss failed");
            });

            assertEquals(List.of("start", "dismiss", "navigate"), events);
        });
    }

    /// Minimal executor fixture that records start and optionally fails it.
    @NotNullByDefault
    private static final class RecordingExecutor extends TaskExecutor {
        /// Mutable event sequence shared with the test.
        private final List<String> events;

        /// Whether start should throw.
        private final boolean failStart;

        /// Creates one stopped executor fixture.
        private RecordingExecutor(List<String> events, boolean failStart) {
            super(Task.runAsync("fixture", () -> { }));
            this.events = events;
            this.failStart = failStart;
        }

        /// Records one start request.
        @Override
        public TaskExecutor start() {
            events.add("start");
            if (failStart) {
                throw new IllegalStateException("start failed");
            }
            return this;
        }

        /// Reports no running work.
        @Override
        public boolean test() {
            return false;
        }

        /// Records no cancellation command.
        @Override
        public void cancel() {
        }
    }
}
