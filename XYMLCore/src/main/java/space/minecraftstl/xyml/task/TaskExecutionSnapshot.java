// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;

/// Immutable aggregate state for one top-level `TaskExecutor.start()` invocation.
///
/// Internal parallel tasks are exposed through [#tasks()], while this record remains the single row represented by
/// the task manager's top-level list.
///
/// @param id stable invocation ID
/// @param title user-facing top-level title
/// @param status aggregate lifecycle status
/// @param progress aggregate normalized progress, or empty when unknown
/// @param totalProgressWeight aggregate work weight, or zero when no task contributes progress
/// @param completedProgressWeight completed portion of the aggregate work weight
/// @param everRunning whether at least one actual task has entered the running state
/// @param userVisible whether this execution has a top-level user-visible presentation contract; internal task
/// significance does not promote an automatic workflow into active or successful history
/// @param cancelable whether cancellation is currently accepted
/// @param startedAt invocation start timestamp
/// @param endedAt terminal timestamp, or null while active
/// @param failure redacted terminal failure text, or null after success/while active
/// @param tasks immutable actual-task detail rows
/// @param logs immutable top-level and task lifecycle logs
@NotNullByDefault
public record TaskExecutionSnapshot(
        UUID id,
        String title,
        TaskExecutionStatus status,
        OptionalDouble progress,
        double totalProgressWeight,
        double completedProgressWeight,
        boolean everRunning,
        boolean userVisible,
        boolean cancelable,
        Instant startedAt,
        @Nullable Instant endedAt,
        @Nullable String failure,
        @Unmodifiable List<TaskExecutionTaskSnapshot> tasks,
        @Unmodifiable List<TaskExecutionLogEntry> logs) {
    /// Validates and defensively copies one aggregate snapshot.
    public TaskExecutionSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(logs, "logs");
        if (progress.isPresent()) {
            double value = progress.getAsDouble();
            if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
                throw new IllegalArgumentException("progress must be finite and between zero and one");
            }
        }
        if (!Double.isFinite(totalProgressWeight)
                || totalProgressWeight < 0.0D
                || !Double.isFinite(completedProgressWeight)
                || completedProgressWeight < 0.0D
                || completedProgressWeight > totalProgressWeight) {
            throw new IllegalArgumentException("progress weights must be finite and ordered");
        }
        if (status.isTerminal() && cancelable) {
            throw new IllegalArgumentException("a terminal execution cannot be cancelable");
        }
        tasks = List.copyOf(tasks);
        logs = List.copyOf(logs);
    }
}
