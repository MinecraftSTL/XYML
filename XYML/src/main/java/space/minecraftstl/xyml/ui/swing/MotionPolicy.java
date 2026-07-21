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

import java.util.Objects;

/// Controls which categories of Swing animation are allowed to run.
@NotNullByDefault
public enum MotionPolicy {
    /// Runs both essential and decorative animation.
    FULL,

    /// Runs essential animation while applying decorative changes immediately.
    REDUCED,

    /// Applies all animated changes immediately.
    OFF;

    /// Returns whether an animation with the given purpose should advance over time.
    ///
    /// @param purpose the semantic purpose of the animation
    /// @return `true` when the animation should run
    public boolean allows(MotionPurpose purpose) {
        Objects.requireNonNull(purpose);

        return switch (this) {
            case FULL -> true;
            case REDUCED -> purpose == MotionPurpose.ESSENTIAL;
            case OFF -> false;
        };
    }
}
