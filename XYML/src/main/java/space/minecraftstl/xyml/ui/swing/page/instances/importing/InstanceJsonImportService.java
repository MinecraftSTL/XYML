// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.importing;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.task.Task;

import java.nio.file.Path;

/// Creates a cancellable task that imports one launcher instance from a Minecraft version JSON file.
@NotNullByDefault
@FunctionalInterface
public interface InstanceJsonImportService {
    /// Creates a deferred import task without reading or parsing the source on the calling thread.
    ///
    /// @param source local Minecraft version JSON path
    /// @param instanceId validated destination instance ID
    /// @return task that parses, downloads, saves, refreshes, and selects the imported instance
    Task<@Nullable Void> createImportTask(Path source, GameInstanceID instanceId);
}
