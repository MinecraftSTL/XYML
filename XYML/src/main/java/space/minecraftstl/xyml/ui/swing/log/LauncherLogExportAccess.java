// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
