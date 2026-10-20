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

import java.util.List;
import java.util.Objects;

/// Immutable progress information supplied when a failed repair is retried.
///
/// A task factory may use the completed-step list to skip work that it has committed durably. The checkpoint is only
/// advisory to the task implementation: the coordinator still revalidates the launcher-owned source and the task
/// remains responsible for its own rollback and cleanup.
///
/// @param completedSteps steps that reached a successful terminal state in an earlier attempt
/// @param failedSteps steps that failed in the most recent attempt
/// @param resumeFromStep first step that should be retried, or null when no step has failed
@NotNullByDefault
public record RepairCheckpoint(
        @Unmodifiable List<String> completedSteps,
        @Unmodifiable List<String> failedSteps,
        @Nullable String resumeFromStep) {
    /// Validates and defensively copies the checkpoint collections.
    public RepairCheckpoint {
        completedSteps = copyStepNames(completedSteps, "completedSteps");
        failedSteps = copyStepNames(failedSteps, "failedSteps");
        if (resumeFromStep != null && resumeFromStep.isBlank()) {
            throw new IllegalArgumentException("resumeFromStep must not be blank");
        }
    }

    /// Returns an empty checkpoint for a first attempt.
    ///
    /// @return empty immutable checkpoint
    public static RepairCheckpoint initial() {
        return new RepairCheckpoint(List.of(), List.of(), null);
    }

    /// Copies and validates ordered step names.
    ///
    /// @param names source names
    /// @param field field name used in validation errors
    /// @return immutable, duplicate-free names
    private static @Unmodifiable List<String> copyStepNames(List<String> names, String field) {
        Objects.requireNonNull(names, field);
        List<String> copy = names.stream()
                .map(name -> Objects.requireNonNull(name, field + " entry").strip())
                .peek(name -> {
                    if (name.isEmpty()) {
                        throw new IllegalArgumentException(field + " must not contain blank names");
                    }
                })
                .distinct()
                .toList();
        return List.copyOf(copy);
    }
}
