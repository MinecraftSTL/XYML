// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Reports that a task resource lease could not release one or more ownership entries.
///
/// The lock manager retains the affected lease in the current process so a bounded retry can attempt cleanup before
/// another write operation is admitted. The exception deliberately carries only immutable resource descriptions and
/// never exposes the internal owner or lease object.
@NotNullByDefault
public final class TaskResourceCleanupException extends IllegalStateException {
    /// Serialization identifier for this immutable failure type.
    private static final long serialVersionUID = 1L;

    /// Resources whose ownership could not be released during the failed attempt.
    private final @Unmodifiable List<String> resources;

    /// Creates one cleanup failure with stable resource descriptions.
    ///
    /// @param resources resources that still require cleanup
    public TaskResourceCleanupException(@Unmodifiable List<String> resources) {
        super("Task resource cleanup failed: " + String.join(", ", Objects.requireNonNull(resources, "resources")));
        if (resources.isEmpty()) {
            throw new IllegalArgumentException("resources must not be empty");
        }
        this.resources = List.copyOf(resources);
    }

    /// Returns immutable resource descriptions requiring a cleanup retry.
    ///
    /// @return residual resource descriptions
    public @Unmodifiable List<String> resources() {
        return resources;
    }
}
