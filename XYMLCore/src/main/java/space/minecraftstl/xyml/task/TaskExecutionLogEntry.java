// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/// One timestamped, already redacted lifecycle log entry.
///
/// A null task ID denotes an entry belonging to the top-level execution itself.
///
/// @param timestamp event timestamp
/// @param taskId actual task ID, or null for a top-level entry
/// @param event stable event kind
/// @param message redacted human-readable event details
@NotNullByDefault
public record TaskExecutionLogEntry(
        Instant timestamp,
        @Nullable UUID taskId,
        String event,
        String message) {
    /// Validates one immutable log entry.
    public TaskExecutionLogEntry {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(message, "message");
    }
}
