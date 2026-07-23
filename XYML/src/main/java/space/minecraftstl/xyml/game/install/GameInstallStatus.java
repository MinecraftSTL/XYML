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

/// Describes the lifecycle of one game-installation session.
@NotNullByDefault
public enum GameInstallStatus {
    /// The request is creating or starting its task execution chain.
    PREPARING,

    /// The complete installation and repository update finished normally.
    COMPLETED,

    /// Installation stopped because of a non-cancellation failure.
    FAILED,

    /// Installation stopped after cancellation was accepted.
    CANCELLED;

    /// Returns whether no further installation-state transition is possible.
    ///
    /// @return true for every status except [#PREPARING]
    public boolean isTerminal() {
        return this != PREPARING;
    }
}
