// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
