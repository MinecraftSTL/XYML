// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.task.presentation;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.OptionalDouble;

/// Immutable task information consumed by a presentation surface.
///
/// An empty progress value means the task cannot currently quantify its completion. Detail text is an empty
/// string when no diagnostic or explanatory text is available.
///
/// @param title the stable user-facing task title
/// @param phase the current user-facing phase description
/// @param progress the optional normalized progress from zero through one
/// @param status the current lifecycle status
/// @param cancelable whether the task currently accepts a cancellation request
/// @param details optional explanatory or diagnostic text represented by an empty string when absent
@NotNullByDefault
public record TaskSnapshot(
        String title,
        String phase,
        OptionalDouble progress,
        TaskStatus status,
        boolean cancelable,
        String details) {
    /// Validates and creates an immutable task snapshot.
    public TaskSnapshot {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(details, "details");

        if (progress.isPresent()) {
            double value = progress.getAsDouble();
            if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException("progress must be finite and between zero and one");
            }
        }
        if (status.isTerminal() && cancelable) {
            throw new IllegalArgumentException("a terminal task cannot be cancelable");
        }
    }
}
