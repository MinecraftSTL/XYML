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
import org.jetbrains.annotations.Nullable;

import java.awt.DisplayMode;
import java.awt.GraphicsEnvironment;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Selects the Swing animation timer delay from explicit overrides or the active display refresh rate.
@NotNullByDefault
public final class SwingAnimationFrameRateResolver {
    /// JVM property whose positive integer value directly specifies the Swing timer delay in milliseconds.
    private static final String FRAME_DELAY_PROPERTY = "xyml.swing.animationFrameDelayMillis";

    /// Environment variable whose positive integer value specifies animation frames per second.
    private static final String FRAME_RATE_ENVIRONMENT_VARIABLE = "XYML_ANIMATION_FRAME_RATE";

    /// Minimum automatic sampling rate used to avoid missing display refreshes between EDT repaint passes.
    private static final int MINIMUM_AUTOMATIC_FRAME_RATE = 120;

    /// Timer delay used for unknown displays and invalid automatic configuration.
    private static final int DEFAULT_FRAME_DELAY_MILLIS = 8;

    /// Established nearest practical Swing timer delay for an explicit sixty-frame rate.
    private static final int SIXTY_HERTZ_FRAME_DELAY_MILLIS = 16;

    /// Prevents construction of this stateless resolver.
    private SwingAnimationFrameRateResolver() {
    }

    /// Resolves the timer delay for the current process and default AWT display.
    ///
    /// Explicit configuration is ignored while animation is disabled because no timer will advance. A JVM delay
    /// property takes precedence over the frame-rate environment variable. Without either override, every known
    /// positive display refresh rate uses at least 120 Hz sampling while unknown and headless displays retain 8 ms.
    ///
    /// @param animationsDisabled whether the persisted appearance setting disables animation
    /// @return a positive timer delay in milliseconds
    public static int resolveFrameDelayMillis(boolean animationsDisabled) {
        return resolveFrameDelayMillis(
                animationsDisabled,
                System.getProperty(FRAME_DELAY_PROPERTY),
                System.getenv(FRAME_RATE_ENVIRONMENT_VARIABLE),
                SwingAnimationFrameRateResolver::defaultDisplayRefreshRate,
                LOG::warning);
    }

    /// Resolves a timer delay from explicit inputs and a lazily evaluated display refresh rate.
    ///
    /// This boundary keeps process configuration and AWT display access deterministic in unit tests. An explicit
    /// but invalid override logs a warning and returns the default without consulting lower-priority sources.
    ///
    /// @param animationsDisabled whether animation is disabled
    /// @param frameDelayProperty explicit timer delay in milliseconds, or `null` when absent
    /// @param frameRateEnvironment explicit frames per second, or `null` when absent
    /// @param displayRefreshRate lazily supplies the default display refresh rate
    /// @param warningSink receives invalid-configuration and failed-detection warnings
    /// @return a positive timer delay in milliseconds
    static int resolveFrameDelayMillis(
            boolean animationsDisabled,
            @Nullable String frameDelayProperty,
            @Nullable String frameRateEnvironment,
            IntSupplier displayRefreshRate,
            Consumer<String> warningSink) {
        Objects.requireNonNull(displayRefreshRate, "displayRefreshRate");
        Objects.requireNonNull(warningSink, "warningSink");

        if (animationsDisabled) {
            return DEFAULT_FRAME_DELAY_MILLIS;
        }

        if (frameDelayProperty != null) {
            OptionalInt configuredDelay = parsePositiveInteger(frameDelayProperty);
            if (configuredDelay.isPresent()) {
                return configuredDelay.getAsInt();
            }
            warningSink.accept("Invalid Swing animation frame delay: " + frameDelayProperty);
            return DEFAULT_FRAME_DELAY_MILLIS;
        }

        if (frameRateEnvironment != null) {
            OptionalInt configuredFrameRate = parsePositiveInteger(frameRateEnvironment);
            if (configuredFrameRate.isPresent()) {
                return frameDelayForFramesPerSecond(configuredFrameRate.getAsInt());
            }
            warningSink.accept("Invalid animation frame rate: " + frameRateEnvironment);
            return DEFAULT_FRAME_DELAY_MILLIS;
        }

        int detectedRefreshRate;
        try {
            detectedRefreshRate = displayRefreshRate.getAsInt();
        } catch (RuntimeException detectionFailure) {
            warningSink.accept("Failed to detect the display refresh rate: " + detectionFailure);
            return DEFAULT_FRAME_DELAY_MILLIS;
        }
        if (detectedRefreshRate <= 0) {
            return DEFAULT_FRAME_DELAY_MILLIS;
        }
        int samplingRate = Math.max(MINIMUM_AUTOMATIC_FRAME_RATE, detectedRefreshRate);
        return frameDelayForFramesPerSecond(samplingRate);
    }

    /// Reads the refresh rate reported by the default AWT graphics device.
    ///
    /// @return refresh rate in hertz, or `DisplayMode.REFRESH_RATE_UNKNOWN` in a headless environment
    private static int defaultDisplayRefreshRate() {
        if (GraphicsEnvironment.isHeadless()) {
            return DisplayMode.REFRESH_RATE_UNKNOWN;
        }
        return GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDisplayMode()
                .getRefreshRate();
    }

    /// Parses a strictly positive decimal integer without accepting invalid configuration silently.
    ///
    /// @param value configured decimal value
    /// @return parsed value, or empty when malformed or non-positive
    private static OptionalInt parsePositiveInteger(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? OptionalInt.of(parsed) : OptionalInt.empty();
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }

    /// Converts frames per second to an integer Swing timer delay.
    ///
    /// Sixty frames per second retains the launcher's established 16 ms delay instead of rounding 16.67 ms to
    /// 17 ms. Other rates use nearest-millisecond rounding with a one-millisecond lower bound.
    ///
    /// @param framesPerSecond positive animation frame rate
    /// @return positive timer delay in milliseconds
    private static int frameDelayForFramesPerSecond(int framesPerSecond) {
        if (framesPerSecond == 60) {
            return SIXTY_HERTZ_FRAME_DELAY_MILLIS;
        }
        return Math.max(1, (int) Math.round(1000.0 / framesPerSecond));
    }
}
