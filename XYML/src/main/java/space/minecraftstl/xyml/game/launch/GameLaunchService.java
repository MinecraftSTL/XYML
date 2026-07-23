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
