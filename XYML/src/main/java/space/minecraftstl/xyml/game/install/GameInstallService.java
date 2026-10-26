// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.install;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Optional;

/// Starts at most one active vanilla installation and owns its preparation lifetime.
@NotNullByDefault
public interface GameInstallService extends AutoCloseable {
    /// Starts one captured installation request.
    ///
    /// @param request immutable instance name and selected version
    /// @return newly scheduled installation session
    /// @throws GameInstallAlreadyRunningException when another installation owns the slot
    /// @throws IllegalStateException when the service is closed
    GameInstallSession install(GameInstallRequest request);

    /// Returns the session currently preparing or running installation.
    ///
    /// @return active session, or an empty value
    Optional<GameInstallSession> activeInstallation();

    /// Cancels the active session, if any, and rejects future installations.
    @Override
    void close();
}
