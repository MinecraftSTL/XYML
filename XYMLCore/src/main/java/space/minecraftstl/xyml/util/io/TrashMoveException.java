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

/// Reports targets that could not be moved to the recycle bin.
@NotNullByDefault
public final class TrashMoveException extends IOException {
    /// Stable serialization identifier.
    private static final long serialVersionUID = 1L;

    /// Exact paths that could not be moved.
    private final @Unmodifiable List<Path> failedPaths;

    /// Creates one failure from all unresolved recycle-bin targets.
    ///
    /// @param failedPaths paths that remain in their original locations
    public TrashMoveException(@Unmodifiable List<Path> failedPaths) {
        super("Unable to move " + failedPaths.size() + " path(s) to the recycle bin");
        this.failedPaths = List.copyOf(failedPaths);
    }

    /// Returns every target that remained after the recycle-bin attempt.
    ///
    /// @return immutable failed-path snapshot
    public @Unmodifiable List<Path> failedPaths() {
        return failedPaths;
    }
}
