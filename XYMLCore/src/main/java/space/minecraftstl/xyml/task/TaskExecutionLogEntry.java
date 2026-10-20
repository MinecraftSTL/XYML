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
