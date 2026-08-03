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
package space.minecraftstl.xyml.setting;

import org.jetbrains.annotations.NotNullByDefault;

/// Defines the persisted percentage and supported slider grid for launcher animation speed.
///
/// @param percentage current speed where one hundred percent preserves each animation's authored duration
/// @param minimumPercentage slowest supported speed
/// @param maximumPercentage fastest supported speed
/// @param percentageStep supported adjustment increment
@NotNullByDefault
public record AnimationSpeedSettings(
        int percentage,
        int minimumPercentage,
        int maximumPercentage,
        int percentageStep) {
    /// Default animation speed percentage.
    public static final int DEFAULT_PERCENTAGE = 100;

    /// Slowest supported animation speed percentage.
    public static final int MINIMUM_PERCENTAGE = 50;

    /// Fastest supported animation speed percentage.
    public static final int MAXIMUM_PERCENTAGE = 200;

    /// Persisted animation-speed adjustment increment.
    public static final int PERCENTAGE_STEP = 10;

    /// Validates one current value and slider grid.
    public AnimationSpeedSettings {
        if (minimumPercentage <= 0) {
            throw new IllegalArgumentException("minimumPercentage must be positive");
        }
        if (maximumPercentage < minimumPercentage) {
            throw new IllegalArgumentException("maximumPercentage must not precede minimumPercentage");
        }
        if (percentage < minimumPercentage || percentage > maximumPercentage) {
            throw new IllegalArgumentException("percentage must be within the supported range");
        }
        if (percentageStep <= 0) {
            throw new IllegalArgumentException("percentageStep must be positive");
        }
        if ((maximumPercentage - minimumPercentage) % percentageStep != 0) {
            throw new IllegalArgumentException("maximumPercentage must align to percentageStep");
        }
        if ((percentage - minimumPercentage) % percentageStep != 0) {
            throw new IllegalArgumentException("percentage must align to percentageStep");
        }
    }

    /// Returns the production default value and supported slider grid.
    ///
    /// @return default one-hundred-percent speed settings
    public static AnimationSpeedSettings defaults() {
        return new AnimationSpeedSettings(
                DEFAULT_PERCENTAGE,
                MINIMUM_PERCENTAGE,
                MAXIMUM_PERCENTAGE,
                PERCENTAGE_STEP);
    }
}
