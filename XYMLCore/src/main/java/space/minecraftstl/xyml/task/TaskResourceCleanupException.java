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
