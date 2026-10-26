// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
