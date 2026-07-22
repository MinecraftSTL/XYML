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
