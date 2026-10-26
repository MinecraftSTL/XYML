// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.launch.LaunchSession;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/// Starts launch-related maintenance operations through application-owned command services.
@NotNullByDefault
public interface InstanceMaintenanceLaunchActions {
    /// Starts a test-mode launch for the exact managed instance and currently selected account.
    ///
    /// @return observable launch-preparation session
    LaunchSession testLaunch();

    /// Exports a standalone launch script for the exact managed instance and selected account.
    ///
    /// @param scriptFile local destination selected through the native interaction boundary
    /// @return completion yielding the exact normalized generated script path
    CompletionStage<Path> exportLaunchScript(Path scriptFile);
}
