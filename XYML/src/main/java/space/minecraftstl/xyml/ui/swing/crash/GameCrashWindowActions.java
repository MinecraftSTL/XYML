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
package space.minecraftstl.xyml.ui.swing.crash;

import org.jetbrains.annotations.NotNullByDefault;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/// Isolates crash-log export, desktop integration, and log-window presentation from Swing components.
@NotNullByDefault
interface GameCrashWindowActions {
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
}
