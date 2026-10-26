// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.install;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.Serial;
import java.util.Objects;

/// Reports that another installation still owns the service's single-flight slot.
@NotNullByDefault
public final class GameInstallAlreadyRunningException extends IllegalStateException {
    /// Serialization identifier for this stable exception shape.
    @Serial
    private static final long serialVersionUID = 1L;

    /// Request currently occupying the installation slot.
    private final GameInstallRequest activeRequest;

    /// Creates a conflict carrying the request that won the slot.
    ///
    /// @param activeRequest request currently being installed
    public GameInstallAlreadyRunningException(GameInstallRequest activeRequest) {
        super("A game installation is already active: "
                + Objects.requireNonNull(activeRequest, "activeRequest"));
        this.activeRequest = activeRequest;
    }

    /// Returns the request currently occupying the slot.
    ///
    /// @return active installation request
    public GameInstallRequest activeRequest() {
        return activeRequest;
    }
}
