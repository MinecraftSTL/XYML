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
package space.minecraftstl.xyml.util.io;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/// Reports every path that remained after a permanent batch deletion.
@NotNullByDefault
public final class DeletionBatchException extends IOException {
    /// Stable serialization identifier.
    private static final long serialVersionUID = 1L;

    /// Exact paths that could not be permanently removed.
    private final @Unmodifiable List<Path> failedPaths;

    /// Creates one aggregate permanent-deletion failure.
    ///
    /// @param failedPaths paths that remain after all batch attempts
    public DeletionBatchException(@Unmodifiable List<Path> failedPaths) {
        super("Unable to permanently delete " + failedPaths.size() + " path(s)");
        this.failedPaths = List.copyOf(failedPaths);
    }

    /// Returns every path that remained after all batch attempts.
    ///
    /// @return immutable failed-path snapshot
    public @Unmodifiable List<Path> failedPaths() {
        return failedPaths;
    }
}
