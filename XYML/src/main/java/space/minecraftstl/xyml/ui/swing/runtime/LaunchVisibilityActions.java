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
package space.minecraftstl.xyml.ui.swing.runtime;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Defines toolkit-neutral launcher-window actions applied at game-process lifecycle boundaries.
///
/// Each command must be safe to invoke from a process-completion thread. The production runtime
/// serializes native window work onto the Swing EDT and prevents show or hide callbacks from
/// reopening a runtime whose close transition has already started.
///
/// @param close closes the complete launcher runtime
/// @param hide hides the launcher window without disposing it
/// @param show shows the existing launcher window when the runtime remains open
@NotNullByDefault
public record LaunchVisibilityActions(Runnable close, Runnable hide, Runnable show) {
    /// Validates the complete action boundary.
    public LaunchVisibilityActions {
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(hide, "hide");
        Objects.requireNonNull(show, "show");
    }
}
