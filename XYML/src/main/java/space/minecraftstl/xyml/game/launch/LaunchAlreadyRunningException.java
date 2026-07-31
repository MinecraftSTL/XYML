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
