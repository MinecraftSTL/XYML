// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.log;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.List;

/// Isolates process, clipboard, and filesystem side effects used by the Swing game log window.
@NotNullByDefault
interface GameLogWindowActions {
    /// Registers a process-exit callback.
    ///
    /// Implementations must not require the caller to retain a registration handle. The supplied callback is expected
    /// to hold its window through a weak reference so a long-running process cannot retain a closed window.
    ///
    /// @param listener callback invoked after raw process termination
    void registerProcessExit(Runnable listener);

    /// Reports whether the game process is still running.
    ///
    /// @return true while termination controls should remain available
    boolean isProcessRunning();

    /// Reports whether a JVM stack dump can currently be generated.
    ///
    /// @return true when attachment support and a running process are both available
    boolean canExportDump();

    /// Stops the game process and its related stream threads.
    void terminateGame();

    /// Writes retained log lines to a newly named export file.
    ///
    /// @param lines immutable text snapshot in source order
    /// @return absolute path of the exported file
    /// @throws Exception when the export cannot be written
    Path exportLogs(@Unmodifiable List<String> lines) throws Exception;

    /// Generates a JVM stack dump for the running game process.
    ///
    /// @return absolute path of the generated dump
    /// @throws Exception when attachment or writing fails
    Path exportDump() throws Exception;

    /// Reveals an exported file using the native file manager when possible.
    ///
    /// @param file exported file to reveal
    /// @throws Exception when neither native reveal nor the desktop fallback succeeds
    void revealFile(Path file) throws Exception;

    /// Replaces the system clipboard text with selected log lines.
    ///
    /// @param text selected log text
    void copyText(String text);
}
