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

/// Describes the preparation lifecycle of one launch session without implying process liveness.
@NotNullByDefault
public enum LaunchStatus {
    /// The launch task is being created or is preparing the managed process.
    PREPARING,

    /// Preparation produced a managed process; the process may subsequently exit independently.
    PROCESS_CREATED,

    /// Preparation terminated because of a non-cancellation failure.
    FAILED,

    /// Preparation terminated after cancellation before a managed process was produced.
    CANCELLED;

    /// Returns whether no further preparation-state transition is possible.
    ///
    /// @return true for every state except [#PREPARING]
    public boolean isTerminal() {
        return this != PREPARING;
    }
}
