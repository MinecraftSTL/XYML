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
package space.minecraftstl.xyml.ui.swing.page.home;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.launch.LaunchRequest;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/// Starts one standalone launch-script export for an immutable launcher selection.
///
/// Implementations own launcher-state resolution and background task lifetime. The Swing home model only captures
/// stable identifiers and observes the completion stage, keeping file chooser and task execution concerns separate.
@FunctionalInterface
@NotNullByDefault
public interface HomeLaunchScriptExportCommand {
    /// Starts one export using the captured account, game-directory, and instance identifiers.
    ///
    /// @param request stable account and instance selection
    /// @param scriptFile local destination selected by the user
    /// @return completion stage yielding the exact generated script path
    CompletionStage<Path> export(LaunchRequest request, Path scriptFile);

    /// Returns a command that rejects exports when a test or incomplete composition has no export service.
    ///
    /// @return non-null unavailable command
    static HomeLaunchScriptExportCommand unavailable() {
        return (request, scriptFile) -> {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(scriptFile, "scriptFile");
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Launch-script export is unavailable"));
        };
    }
}
