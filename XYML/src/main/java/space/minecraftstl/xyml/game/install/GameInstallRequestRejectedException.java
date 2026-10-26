// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.install;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.Serial;
import java.util.Objects;

/// Reports a destination validation failure that presentation code can localize by reason.
@NotNullByDefault
public final class GameInstallRequestRejectedException extends IllegalArgumentException {
    /// Serialization identifier for this stable exception shape.
    @Serial
    private static final long serialVersionUID = 1L;

    /// Rejected request preserved exactly for diagnostics and retry UI.
    private final GameInstallRequest request;

    /// Stable rejection category independent of localized text.
    private final Reason reason;

    /// Creates a rejected-request failure.
    ///
    /// @param request exact rejected request
    /// @param reason stable rejection category
    public GameInstallRequestRejectedException(GameInstallRequest request, Reason reason) {
        super("Game installation request rejected (" + Objects.requireNonNull(reason, "reason")
                + "): " + Objects.requireNonNull(request, "request"));
        this.request = request;
        this.reason = reason;
    }

    /// Returns the exact rejected request.
    ///
    /// @return rejected request
    public GameInstallRequest request() {
        return request;
    }

    /// Returns the stable rejection category.
    ///
    /// @return rejection reason
    public Reason reason() {
        return reason;
    }

    /// Enumerates repository validation failures without embedding presentation text.
    @NotNullByDefault
    public enum Reason {
        /// The requested instance name cannot be represented safely on the current platform.
        INVALID_INSTANCE_NAME,

        /// An existing instance already owns the requested identifier.
        INSTANCE_ALREADY_EXISTS
    }
}
