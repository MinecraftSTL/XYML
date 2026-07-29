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
import space.minecraftstl.xyml.Metadata;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Adapts the process-wide launcher logger to the testable launcher-log export boundary.
@NotNullByDefault
public final class LauncherLogExportAccessAdapter implements LauncherLogExportAccess {
    /// Prevents construction outside the explicit launcher adapter factory.
    private LauncherLogExportAccessAdapter() {
    }

    /// Creates an adapter backed by the active launcher logger and current working directory.
    ///
    /// @return production launcher-log export adapter
    public static LauncherLogExportAccessAdapter createForCurrentLauncher() {
        return new LauncherLogExportAccessAdapter();
    }

    /// Returns the active logger file, which may be absent during in-memory-only logging.
    ///
    /// @return active log file, or null when the logger has not opened a file
    @Override
    public @Nullable Path currentLogFile() {
        return LOG.getLogFile();
    }

    /// Returns the logger's immutable recent-file snapshot.
    ///
    /// @param maximumCount maximum history length
    /// @return immutable historical log file paths
    @Override
    public @Unmodifiable List<Path> findRecentLogFiles(int maximumCount) {
        return LOG.findRecentLogFiles(maximumCount);
    }

    /// Streams current launcher log events through the logger's serialized export operation.
    ///
    /// @param output output receiving the current log stream
    /// @throws IOException when the logger cannot write its retained events
    @Override
    public void writeCurrentLogs(OutputStream output) throws IOException {
        LOG.exportLogs(output);
    }

    /// Returns the launcher working directory used by the historical JavaFX export action.
    ///
    /// @return normalized launcher current directory
    @Override
    public Path outputDirectory() {
        return Metadata.CURRENT_DIRECTORY;
    }
}
