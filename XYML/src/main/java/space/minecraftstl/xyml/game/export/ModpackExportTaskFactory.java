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
