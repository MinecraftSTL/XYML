// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.launch;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.Serial;
import java.util.Objects;

/// Reports that another session still owns the process-preparation slot.
@NotNullByDefault
public final class LaunchAlreadyRunningException extends IllegalStateException {
    /// Serialization identifier for this stable exception shape.
    @Serial
    private static final long serialVersionUID = 1L;

    /// Request currently occupying the preparation slot.
    private final LaunchRequest activeRequest;

    /// Creates a conflict carrying the request that won the single-flight race.
    ///
    /// @param activeRequest request currently being prepared
    public LaunchAlreadyRunningException(LaunchRequest activeRequest) {
        super("A launch is already being prepared: " + Objects.requireNonNull(activeRequest, "activeRequest"));
        this.activeRequest = activeRequest;
    }

    /// Returns the request currently occupying the preparation slot.
    ///
    /// @return active preparation request
    public LaunchRequest activeRequest() {
        return activeRequest;
    }
}
