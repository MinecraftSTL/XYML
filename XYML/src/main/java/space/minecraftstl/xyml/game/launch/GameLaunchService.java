// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.launch;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Optional;

/// Starts launch preparations under a single-flight policy and owns only their preparation lifetime.
@NotNullByDefault
public interface GameLaunchService extends AutoCloseable {
    /// Starts preparing the captured request.
    ///
    /// @param request stable account, directory, and instance identifiers
    /// @return the newly started preparation session
    /// @throws LaunchAlreadyRunningException when another request is still preparing a process
    /// @throws IllegalStateException when the service has been closed
    LaunchSession launch(LaunchRequest request);

    /// Returns the session currently preparing a process.
    ///
    /// Successfully created processes are deliberately absent because their lifetime is not service-owned.
    ///
    /// @return active preparation, or an empty value
    Optional<LaunchSession> activePreparation();

    /// Cancels the current preparation, if any, and rejects future launches.
    ///
    /// Already created processes are not stopped.
    @Override
    void close();
}
