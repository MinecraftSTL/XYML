// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
