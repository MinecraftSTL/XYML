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
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Defines one persisted launcher animation speed on the supported discrete slider scale.
///
/// @param percentage current speed where one hundred preserves authored duration and the instant sentinel disables
/// frames
@NotNullByDefault
public record AnimationSpeedSettings(int percentage) {
    /// Default animation speed percentage.
    public static final int DEFAULT_PERCENTAGE = 100;

    /// Slowest supported animation speed percentage.
    public static final int MINIMUM_PERCENTAGE = 10;

    /// Fastest finite animation speed percentage.
    public static final int MAXIMUM_FINITE_PERCENTAGE = 500;

    /// Persisted sentinel representing infinite speed and therefore no animation frames.
    public static final int INSTANT_PERCENTAGE = 600;

    /// Ordered percentages represented by consecutive slider positions.
    private static final @Unmodifiable List<Integer> SUPPORTED_PERCENTAGES = List.of(
            10, 20, 30, 40, 50, 60, 70, 80, 90, 100,
            120, 140, 160, 180, 200,
            250, 300, 350, 400, 450, 500,
            INSTANT_PERCENTAGE);

    /// Validates one current value against the launcher-wide discrete scale.
    public AnimationSpeedSettings {
        if (!isSupportedPercentage(percentage)) {
            throw new IllegalArgumentException("Unsupported animation speed percentage: " + percentage);
        }
    }

    /// Returns the production default value and supported slider grid.
    ///
    /// @return default one-hundred-percent speed settings
    public static AnimationSpeedSettings defaults() {
        return new AnimationSpeedSettings(DEFAULT_PERCENTAGE);
    }

    /// Returns whether a percentage is one of the finite values or the instant endpoint.
    ///
    /// @param candidate percentage to validate
    /// @return true when the percentage has a slider position
    public static boolean isSupportedPercentage(int candidate) {
        return SUPPORTED_PERCENTAGES.contains(candidate);
    }

    /// Normalizes an externally edited finite percentage to its nearest supported value.
    ///
    /// The instant endpoint is preserved only when explicitly persisted. Invalid values above the finite range clamp
    /// to five-times speed instead of unexpectedly disabling animation.
    ///
    /// @param candidate raw persisted percentage
    /// @return nearest supported finite percentage, or the explicit instant endpoint
    public static int normalizePercentage(int candidate) {
        if (candidate == INSTANT_PERCENTAGE) {
            return INSTANT_PERCENTAGE;
        }
        if (candidate <= MINIMUM_PERCENTAGE) {
            return MINIMUM_PERCENTAGE;
        }
        if (candidate >= MAXIMUM_FINITE_PERCENTAGE) {
            return MAXIMUM_FINITE_PERCENTAGE;
        }
        int best = MINIMUM_PERCENTAGE;
        long bestDistance = Math.abs((long) candidate - best);
        for (int supported : SUPPORTED_PERCENTAGES) {
            if (supported == INSTANT_PERCENTAGE) {
                break;
            }
            long distance = Math.abs((long) candidate - supported);
            if (distance < bestDistance) {
                best = supported;
                bestDistance = distance;
            }
        }
        return best;
    }

    /// Returns the number of positions on the animation-speed slider.
    ///
    /// @return finite positions plus the instant endpoint
    public static int sliderPositionCount() {
        return SUPPORTED_PERCENTAGES.size();
    }

    /// Resolves one slider position to its persisted speed percentage.
    ///
    /// @param position zero-based slider position
    /// @return supported percentage represented by the position
    /// @throws IndexOutOfBoundsException if the position is outside the slider scale
    public static int percentageAtSliderPosition(int position) {
        return SUPPORTED_PERCENTAGES.get(position);
    }

    /// Resolves one supported percentage to its zero-based slider position.
    ///
    /// @param supportedPercentage supported finite percentage or instant endpoint
    /// @return zero-based slider position
    /// @throws IllegalArgumentException if the percentage is unsupported
    public static int sliderPositionForPercentage(int supportedPercentage) {
        int position = SUPPORTED_PERCENTAGES.indexOf(supportedPercentage);
        if (position < 0) {
            throw new IllegalArgumentException(
                    "Unsupported animation speed percentage: " + supportedPercentage);
        }
        return position;
    }

    /// Returns whether the current slider position requests instant visual changes.
    ///
    /// @return true when the current percentage equals the instant endpoint
    public boolean isInstant() {
        return percentage == INSTANT_PERCENTAGE;
    }
}
