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
package space.minecraftstl.xyml.ui.swing.log;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

/// Supplies the launcher log stream, its historical on-disk files, and the trusted export destination.
///
/// Implementations must return only launcher-owned historical paths. The export service nevertheless validates every
/// returned candidate before opening it, so a faulty adapter cannot cause unrelated files to be archived.
@NotNullByDefault
public interface LauncherLogExportAccess {
    /// Returns the active launcher log file, or null while logging is retained only in memory.
    ///
    /// @return active on-disk log file, or null when no log file exists
    @Nullable Path currentLogFile();

    /// Returns up to the requested number of recent historical launcher log files in chronological source order.
    ///
    /// @param maximumCount maximum number of files to return
    /// @return immutable historical launcher log paths
    @Unmodifiable List<Path> findRecentLogFiles(int maximumCount);

    /// Writes the current in-memory launcher log stream to the supplied output.
    ///
    /// @param output destination receiving the current launcher log text
    /// @throws IOException when the log stream cannot be written
    void writeCurrentLogs(OutputStream output) throws IOException;

    /// Returns the directory where user-requested launcher log archives are written.
    ///
    /// @return trusted export directory
    Path outputDirectory();
}
