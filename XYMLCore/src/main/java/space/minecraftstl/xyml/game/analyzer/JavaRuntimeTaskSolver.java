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

/// Solver adapter that keeps Java-runtime candidate selection inside one repair reason.
@NotNullByDefault
record JavaRuntimeTaskSolver(
        String messageKey,
        @Unmodifiable List<Object> messageArguments,
        String fallbackMessage,
        RepairActionDescriptor repairAction,
        LogAnalyzable.JavaRuntimeRepair repair,
        @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates) implements Solver {
    /// Defensively copies metadata and validates the application repair boundary.
    JavaRuntimeTaskSolver {
        Objects.requireNonNull(messageKey, "messageKey");
        messageArguments = List.copyOf(Objects.requireNonNull(messageArguments, "messageArguments"));
        Objects.requireNonNull(fallbackMessage, "fallbackMessage");
        Objects.requireNonNull(repairAction, "repairAction");
        Objects.requireNonNull(repair, "repair");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    }

    /// Binds the automatic task to a presentation-neutral wizard step.
    ///
    /// @param configurator presentation-neutral step configurator
    @Override
    public void configure(SolverConfigurator configurator) {
        Objects.requireNonNull(configurator, "configurator").bindTask(createTask());
    }

    /// Advances to the next diagnosis after the selected task completes.
    ///
    /// @param configurator presentation-neutral step configurator
    /// @param selectionId completed-task selection identifier
    @Override
    public void callbackSelection(SolverConfigurator configurator, int selectionId) {
        Objects.requireNonNull(configurator, "configurator").transferTo(null);
    }

    /// Returns the immutable candidates captured while this analysis result was created.
    ///
    /// @return candidate snapshot
    @Override
    public @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates() {
        return candidates;
    }

    /// Creates the ordinary automatic selection task.
    ///
    /// @return stopped repair task
    @Override
    public Task<?> createTask() {
        return requireReady(repair.createTask());
    }

    /// Creates a stopped task bound to one explicit candidate.
    ///
    /// @param candidateId selected candidate identifier, or null for ordinary automatic selection
    /// @return stopped repair task
    @Override
    public Task<?> createTask(@Nullable String candidateId) {
        return requireReady(repair.createTask(candidateId));
    }

    /// Creates a candidate-bound task while forwarding retained retry progress to the application boundary.
    ///
    /// @param candidateId selected candidate identifier, or null for ordinary automatic selection
    /// @param checkpoint immutable progress from an earlier repair attempt
    /// @return stopped repair task
    @Override
    public Task<?> createTask(@Nullable String candidateId, RepairCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        return requireReady(repair.createTask(candidateId, checkpoint));
    }

    /// Ensures the application boundary returns a fresh stopped task.
    ///
    /// @param task task returned by the application boundary
    /// @return validated task
    private static Task<?> requireReady(Task<?> task) {
        Task<?> checkedTask = Objects.requireNonNull(task, "repair task factory result");
        if (checkedTask.getState() != Task.TaskState.READY) {
            throw new IllegalStateException("Repair task factory must return a task in the ready state");
        }
        return checkedTask;
    }
}
