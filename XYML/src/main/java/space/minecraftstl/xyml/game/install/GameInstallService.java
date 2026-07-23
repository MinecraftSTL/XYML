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
