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
import org.junit.jupiter.api.Test;

import java.util.Objects;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies that launch progress handles stay bound to one top-level execution.
@NotNullByDefault
public final class TaskExecutionProgressHandleTest {
    /// Progress from another top-level execution cannot change the bound launch progress.
    @Test
    public void ignoresOtherTopLevelExecutionProgress() {
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        ProgressTask firstTask = new ProgressTask();
        ProgressTask secondTask = new ProgressTask();
        TaskExecutionRegistry.Execution firstExecution = registry.begin(
                new NoopExecutor(firstTask),
                "first",
                true);
        TaskExecutionRegistry.Execution secondExecution = registry.begin(
                new NoopExecutor(secondTask),
                "second",
                true);
        TaskExecutionProgressHandle handle = TaskExecutionProgressHandle.forRegistry(registry);
        handle.bind(firstExecution.id());

        firstExecution.taskRunning(null, firstTask);
        firstTask.report(0.25);
        assertEquals(0.25, handle.progress().orElseThrow());

        secondExecution.taskRunning(null, secondTask);
        secondTask.report(0.8);
        assertEquals(0.25, handle.progress().orElseThrow());
        assertEquals(0.8, Objects.requireNonNull(registry.snapshot(secondExecution.id())).progress().orElseThrow());

        firstTask.report(0.5);
        assertEquals(0.5, handle.progress().orElseThrow());
    }

    /// A repeated executor start cannot retarget the handle to another invocation.
    @Test
    public void repeatedBindingCannotFollowAnotherExecution() {
        TaskExecutionProgressHandle handle = TaskExecutionProgressHandle.forRegistry(new TaskExecutionRegistry());
        java.util.UUID first = java.util.UUID.randomUUID();
        java.util.UUID second = java.util.UUID.randomUUID();
        handle.bind(first);
        handle.bind(first);
        handle.bind(second);
        assertEquals(OptionalDouble.empty(), handle.progress());
    }

    /// Minimal executor authority needed to create isolated registry records.
    @NotNullByDefault
    private static final class NoopExecutor extends TaskExecutor {
        /// Creates a no-op executor for one root task.
        private NoopExecutor(Task<?> task) {
            super(task);
        }

        /// Leaves the registry record under direct test control.
        @Override
        public TaskExecutor start() {
            return this;
        }

        /// Ignores cancellation because the direct registry test owns lifecycle transitions.
        @Override
        public void cancel() {
        }

        /// Returns the unused test result.
        @Override
        public boolean test() {
            return false;
        }
    }

    /// Root task exposing deterministic external progress updates.
    @NotNullByDefault
    private static final class ProgressTask extends Task<Void> {
        /// Publishes one normalized progress value.
        private void report(double value) {
            updateProgressImmediately(value);
        }

        /// The direct registry test controls lifecycle transitions externally.
        @Override
        public void execute() {
        }
    }
}
