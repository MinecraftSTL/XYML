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
