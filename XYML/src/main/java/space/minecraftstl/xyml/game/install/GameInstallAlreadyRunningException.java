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
