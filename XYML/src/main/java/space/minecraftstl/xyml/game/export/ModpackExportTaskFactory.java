// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.export;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.Task;

import java.nio.file.Path;

/// Creates stopped modpack-export tasks for presentation-independent workflows.
@FunctionalInterface
@NotNullByDefault
public interface ModpackExportTaskFactory {
    /// Creates a stopped task that publishes the requested archive on success.
    ///
    /// @param request immutable export request
    /// @return stopped task whose result is the final archive path
    Task<Path> create(ModpackExportRequest request);
}
