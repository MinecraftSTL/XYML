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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/// Missing-dependency diagnosis that retains every stable ID with an optional application search boundary.
///
/// @param messageKey localization key used by presentation layers
/// @param messageArguments immutable formatting arguments
/// @param fallbackMessage presentation-independent diagnosis text
/// @param dependencyIds immutable dependency identifiers in stable source order
/// @param search application-owned read-only search boundary, or null for information-only analysis
/// @param gameVersion analyzed game version, or null when unavailable
@NotNullByDefault
record MissingDependencySolver(
        String messageKey,
        @Unmodifiable List<Object> messageArguments,
        String fallbackMessage,
        @Unmodifiable List<String> dependencyIds,
        LogAnalyzable.@Nullable MissingDependencySearch search,
        @Nullable String gameVersion) implements Solver {
    /// Validates metadata and snapshots every mutable input.
    MissingDependencySolver {
        Objects.requireNonNull(messageKey, "messageKey");
        messageArguments = List.copyOf(Objects.requireNonNull(messageArguments, "messageArguments"));
        Objects.requireNonNull(fallbackMessage, "fallbackMessage");
        dependencyIds = List.copyOf(Objects.requireNonNull(dependencyIds, "dependencyIds"));
        RepairActionDescriptor.openModSearch(dependencyIds, search != null);
    }

    /// Returns the immutable read-only search action for every retained dependency.
    ///
    /// @return executable or information-only missing-dependency search action
    @Override
    public RepairActionDescriptor repairAction() {
        return RepairActionDescriptor.openModSearch(dependencyIds, search != null);
    }

    /// Configures either a fresh search task or information-only guidance.
    ///
    /// @param configurator presentation-neutral step configurator
    @Override
    public void configure(SolverConfigurator configurator) {
        SolverConfigurator checkedConfigurator = Objects.requireNonNull(configurator, "configurator");
        @Nullable Task<?> task = createTask();
        if (task == null) {
            checkedConfigurator.setDescription(messageKey, messageArguments, fallbackMessage);
        } else {
            checkedConfigurator.bindTask(task);
        }
    }

    /// Advances after guidance is acknowledged or a search task completes.
    ///
    /// @param configurator presentation-neutral step configurator
    /// @param selectionId completed-task selection identifier
    @Override
    public void callbackSelection(SolverConfigurator configurator, int selectionId) {
        Objects.requireNonNull(configurator, "configurator").transferTo(null);
    }

    /// Creates a fresh stopped task over the complete dependency snapshot when search is available.
    ///
    /// @return independent ready search task, or null for information-only analysis
    @Override
    public @Nullable Task<?> createTask() {
        return search == null ? null : requireReady(search.createTask(dependencyIds, gameVersion));
    }

    /// Creates a fresh search task while rejecting unsupported candidate selection.
    ///
    /// @param candidateId candidate identifier, which is unsupported for dependency search
    /// @param checkpoint immutable progress retained by a previous attempt
    /// @return independent ready search task, or null for information-only analysis
    @Override
    public @Nullable Task<?> createTask(@Nullable String candidateId, RepairCheckpoint checkpoint) {
        if (candidateId != null) {
            throw new IllegalArgumentException("Repair candidate selection is unavailable");
        }
        Objects.requireNonNull(checkpoint, "checkpoint");
        return createTask();
    }

    /// Merges another source's dependency snapshot without losing first-seen order or optional search capability.
    ///
    /// @param later later physical source's solver
    /// @return merged missing-dependency solver
    MissingDependencySolver merge(MissingDependencySolver later) {
        MissingDependencySolver checkedLater = Objects.requireNonNull(later, "later");
        LinkedHashSet<String> mergedIds = new LinkedHashSet<>(dependencyIds);
        mergedIds.addAll(checkedLater.dependencyIds);
        @Unmodifiable List<Object> mergedArguments = mergeMessageArguments(
                messageArguments,
                checkedLater.messageArguments);
        String mergedFallback = fallbackMessage.equals(checkedLater.fallbackMessage)
                ? fallbackMessage
                : fallbackMessage + " " + checkedLater.fallbackMessage;
        LogAnalyzable.@Nullable MissingDependencySearch mergedSearch =
                checkedLater.search == null ? search : checkedLater.search;
        return new MissingDependencySolver(
                checkedLater.messageKey,
                mergedArguments,
                mergedFallback,
                List.copyOf(mergedIds),
                mergedSearch,
                checkedLater.gameVersion == null ? gameVersion : checkedLater.gameVersion);
    }

    /// Preserves detailed dependency summaries when both analyzers expose one string formatting argument.
    ///
    /// @param earlier earlier source arguments
    /// @param later later source arguments
    /// @return merged arguments, or the later snapshot for an unknown argument contract
    private static @Unmodifiable List<Object> mergeMessageArguments(
            @Unmodifiable List<Object> earlier,
            @Unmodifiable List<Object> later) {
        if (earlier.size() == 1
                && later.size() == 1
                && earlier.get(0) instanceof String earlierSummary
                && later.get(0) instanceof String laterSummary) {
            if (earlierSummary.equals(laterSummary)) {
                return later;
            }
            return List.of(earlierSummary + ", " + laterSummary);
        }
        return later;
    }

    /// Verifies that a search boundary returns a fresh stopped task.
    ///
    /// @param task task returned by the application search boundary
    /// @return validated task
    private static Task<?> requireReady(Task<?> task) {
        Task<?> checkedTask = Objects.requireNonNull(task, "missing-dependency search task");
        if (checkedTask.getState() != Task.TaskState.READY) {
            throw new IllegalStateException("Missing-dependency search must return a task in the ready state");
        }
        return checkedTask;
    }
}
