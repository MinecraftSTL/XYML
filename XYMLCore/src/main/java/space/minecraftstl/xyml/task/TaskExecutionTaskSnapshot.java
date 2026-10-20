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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;

/// Immutable detail row for one actual task within a top-level execution.
///
/// @param id stable task ID for this execution
/// @param parentId parent task ID, or null for the root task
/// @param name task display name
/// @param stage resolved task stage, or null when no stage is assigned
/// @param significance task visibility significance
/// @param status task lifecycle state
/// @param progress normalized progress, or empty when unknown
/// @param startedAt first lifecycle timestamp
/// @param endedAt terminal timestamp, or null while active
/// @param failure redacted task failure text, or null when no failure was recorded
/// @param logs immutable task-scoped log entries
@NotNullByDefault
public record TaskExecutionTaskSnapshot(
        UUID id,
        @Nullable UUID parentId,
        String name,
        @Nullable String stage,
        Task.TaskSignificance significance,
        TaskExecutionTaskStatus status,
        OptionalDouble progress,
        Instant startedAt,
        @Nullable Instant endedAt,
        @Nullable String failure,
        @Unmodifiable List<TaskExecutionLogEntry> logs) {
    /// Validates and defensively copies one task snapshot.
    public TaskExecutionTaskSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(significance, "significance");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(logs, "logs");
        if (progress.isPresent()) {
            double value = progress.getAsDouble();
            if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
                throw new IllegalArgumentException("progress must be finite and between zero and one");
            }
        }
        logs = List.copyOf(logs);
    }
}
