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
package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.task.Task;

import java.util.List;
import java.util.Objects;

/// Automatic repair solver backed by a fresh stopped-task factory.
///
/// @param messageKey localization key used by non-wizard presentation layers
/// @param messageArguments immutable localization arguments
/// @param fallbackMessage presentation-independent English repair text
/// @param repairAction immutable structured repair proposal
/// @param taskFactory factory that creates one independent stopped task per invocation
@NotNullByDefault
record TaskSolver(
        String messageKey,
        @Unmodifiable List<Object> messageArguments,
        String fallbackMessage,
        RepairActionDescriptor repairAction,
        RepairTaskFactory taskFactory) implements Solver {
    /// Defensively copies repair metadata and validates the task factory.
    TaskSolver {
        Objects.requireNonNull(messageKey, "messageKey");
        messageArguments = List.copyOf(Objects.requireNonNull(messageArguments, "messageArguments"));
        Objects.requireNonNull(fallbackMessage, "fallbackMessage");
        Objects.requireNonNull(repairAction, "repairAction");
        Objects.requireNonNull(taskFactory, "taskFactory");
    }

    /// Binds the automatic repair task to the current solver step.
    ///
    /// @param configurator presentation-neutral step configurator
    @Override
    public void configure(SolverConfigurator configurator) {
        Objects.requireNonNull(configurator, "configurator").bindTask(createTask());
    }

    /// Advances after the automatic repair task completes.
    ///
    /// @param configurator presentation-neutral step configurator
    /// @param selectionId completed-task selection identifier
    @Override
    public void callbackSelection(SolverConfigurator configurator, int selectionId) {
        Objects.requireNonNull(configurator, "configurator").transferTo(null);
    }

    /// Creates a fresh stopped task for one explicit repair execution.
    ///
    /// @return independent repair task in the ready state
    @Override
    public Task<?> createTask() {
        return requireReady(taskFactory.createTask());
    }

    /// Creates a fresh task while forwarding retained progress to the underlying factory.
    ///
    /// @param candidateId candidate identifier, which is unsupported for this generic solver
    /// @param checkpoint immutable progress from an earlier repair attempt
    /// @return independent repair task in the ready state
    @Override
    public Task<?> createTask(@Nullable String candidateId, RepairCheckpoint checkpoint) {
        if (candidateId != null) {
            throw new IllegalArgumentException("Repair candidate selection is unavailable");
        }
        Objects.requireNonNull(checkpoint, "checkpoint");
        return requireReady(taskFactory.createTask(checkpoint));
    }

    /// Verifies that one factory result is a fresh stopped task.
    ///
    /// @param task task returned by the factory
    /// @return the validated task
    private static Task<?> requireReady(Task<?> task) {
        Task<?> checkedTask = Objects.requireNonNull(task, "repair task factory result");
        if (checkedTask.getState() != Task.TaskState.READY) {
            throw new IllegalStateException("Repair task factory must return a task in the ready state");
        }
        return checkedTask;
    }
}
