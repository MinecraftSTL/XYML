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
package space.minecraftstl.xyml.ui.swing.page.instances.importing;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
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
    Task<@Nullable Void> createImportTask(Path source, String instanceId);
}
