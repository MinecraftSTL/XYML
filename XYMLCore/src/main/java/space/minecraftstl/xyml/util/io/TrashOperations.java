// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util.io;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/// Provides platform recycle-bin capability checks and target moves.
@NotNullByDefault
public interface TrashOperations {
    /// Returns whether this platform can move a path to a recycle bin.
    ///
    /// @return whether the recycle bin is available
    boolean isSupported();

    /// Attempts to move one path to the platform recycle bin.
    ///
    /// @param path exact file, directory, or symbolic link to move
    /// @return whether the target was moved successfully
    boolean moveToTrash(Path path);
}
