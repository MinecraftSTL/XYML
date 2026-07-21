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
package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;

/// Exposes lifecycle control for one Swing animation.
@NotNullByDefault
public interface AnimationHandle {
    /// Cancels the animation without invoking its completion callback.
    ///
    /// Calling this method after cancellation or completion has no effect.
    void cancel();

    /// Returns whether the animation is currently waiting for timer frames.
    ///
    /// @return `true` while the animation is running
    boolean isRunning();

    /// Returns whether the animation was explicitly cancelled.
    ///
    /// @return `true` after cancellation
    boolean isCancelled();

    /// Returns whether the animation reached its final state.
    ///
    /// @return `true` after normal or policy-driven completion
    boolean isFinished();
}
