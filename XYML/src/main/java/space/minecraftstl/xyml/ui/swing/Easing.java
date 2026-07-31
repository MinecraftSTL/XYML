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

/// Provides bounded easing curves for Swing animation progress.
@NotNullByDefault
public enum Easing {
    /// Preserves linear progress.
    LINEAR,

    /// Uses a smooth cubic curve with zero velocity at both ends.
    STANDARD,

    /// Uses a cubic deceleration curve for elements entering or settling into place.
    DECELERATE;

    /// Transforms normalized time into normalized visual progress.
    ///
    /// Values outside the normalized range are clamped before applying the selected curve.
    ///
    /// @param progress normalized elapsed time
    /// @return eased progress between zero and one
    public double apply(double progress) {
        double boundedProgress = Math.max(0.0, Math.min(1.0, progress));
        return switch (this) {
            case LINEAR -> boundedProgress;
            case STANDARD -> boundedProgress * boundedProgress * (3.0 - 2.0 * boundedProgress);
            case DECELERATE -> 1.0 - Math.pow(1.0 - boundedProgress, 3.0);
        };
    }
}
