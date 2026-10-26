// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.crash;

import org.jetbrains.annotations.NotNullByDefault;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/// Isolates crash-log export, desktop integration, and log-window presentation from Swing components.
@NotNullByDefault
interface GameCrashWindowActions extends AutoCloseable {
    /// Exports a filtered crash bundle containing process, launcher, version, and recent game logs.
    ///
    /// @return asynchronous absolute zip-file path
    CompletionStage<Path> exportCrashLogs();

    /// Reveals an exported file using the native file manager.
    ///
    /// @param file exported file to reveal
    /// @throws Exception when no supported file-manager integration succeeds
    void revealFile(Path file) throws Exception;

    /// Opens or raises the existing Swing game-log window.
    void showGameLogs();

    /// Opens one trusted help or localized-reason link.
    ///
    /// @param destination link destination
    /// @throws Exception when desktop browsing is unavailable
    void openLink(URI destination) throws Exception;

    /// Releases any window-owned secondary surfaces or resources.
    ///
    /// Stateless action implementations require no cleanup.
    @Override
    default void close() {
    }
}
