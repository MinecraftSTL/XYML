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
