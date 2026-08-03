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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests animation frame-delay precedence, validation, and display-refresh adaptation.
@NotNullByDefault
public final class SwingAnimationFrameRateResolverTest {
    /// An explicit JVM delay takes precedence over the environment and does not inspect the display.
    @Test
    public void systemPropertyHasHighestPriority() {
        AtomicInteger detectionCount = new AtomicInteger();

        int delay = SwingAnimationFrameRateResolver.resolveFrameDelayMillis(
                false,
                "5",
                "144",
                () -> {
                    detectionCount.incrementAndGet();
                    return 165;
                },
                ignored -> { });

        assertAll(
                () -> assertEquals(5, delay),
                () -> assertEquals(0, detectionCount.get()));
    }

    /// An explicit frame-rate environment value takes precedence over automatic display detection.
    @Test
    public void environmentHasPriorityOverDisplayDetection() {
        AtomicInteger detectionCount = new AtomicInteger();

        int delay = SwingAnimationFrameRateResolver.resolveFrameDelayMillis(
                false,
                null,
                "120",
                () -> {
                    detectionCount.incrementAndGet();
                    return 165;
                },
                ignored -> { });

        assertAll(
                () -> assertEquals(8, delay),
                () -> assertEquals(0, detectionCount.get()));
    }

    /// Sixty configured frames per second retain the established 16 ms Swing timer delay.
    @Test
    public void sixtyFramesPerSecondUsesEstablishedDefaultDelay() {
        int delay = SwingAnimationFrameRateResolver.resolveFrameDelayMillis(
                false,
                null,
                "60",
                () -> 144,
                ignored -> { });

        assertEquals(16, delay);
    }

    /// Automatic detection adapts common display refresh rates using nearest-millisecond delays.
    @Test
    public void highRefreshDisplaysUseMatchingDelays() {
        assertAll(
                () -> assertEquals(13, resolveAutomatically(75)),
                () -> assertEquals(11, resolveAutomatically(90)),
                () -> assertEquals(8, resolveAutomatically(120)),
                () -> assertEquals(7, resolveAutomatically(144)),
                () -> assertEquals(6, resolveAutomatically(165)));
    }

    /// Sixty-hertz and unknown displays retain the established default while other known rates adapt.
    @Test
    public void ordinaryOrUnknownDisplaysUseDefaultDelay() {
        assertAll(
                () -> assertEquals(16, resolveAutomatically(0)),
                () -> assertEquals(16, resolveAutomatically(30)),
                () -> assertEquals(16, resolveAutomatically(60)),
                () -> assertEquals(11, resolveAutomatically(89)));
    }

    /// An invalid explicit environment value warns and suppresses automatic detection.
    @Test
    public void invalidEnvironmentSuppressesDisplayDetection() {
        AtomicInteger detectionCount = new AtomicInteger();
        List<String> warnings = new ArrayList<>();

        int delay = SwingAnimationFrameRateResolver.resolveFrameDelayMillis(
                false,
                null,
                "fast",
                () -> {
                    detectionCount.incrementAndGet();
                    return 165;
                },
                warnings::add);

        assertAll(
                () -> assertEquals(16, delay),
                () -> assertEquals(0, detectionCount.get()),
                () -> assertEquals(List.of("Invalid animation frame rate: fast"), warnings));
    }

    /// An invalid explicit JVM delay also blocks lower-priority configuration and warns once.
    @Test
    public void invalidSystemPropertySuppressesLowerPrioritySources() {
        AtomicInteger detectionCount = new AtomicInteger();
        List<String> warnings = new ArrayList<>();

        int delay = SwingAnimationFrameRateResolver.resolveFrameDelayMillis(
                false,
                "0",
                "144",
                () -> {
                    detectionCount.incrementAndGet();
                    return 165;
                },
                warnings::add);

        assertAll(
                () -> assertEquals(16, delay),
                () -> assertEquals(0, detectionCount.get()),
                () -> assertEquals(List.of("Invalid Swing animation frame delay: 0"), warnings));
    }

    /// Disabled animation bypasses overrides, warnings, and display access entirely.
    @Test
    public void disabledAnimationSkipsResolution() {
        AtomicInteger detectionCount = new AtomicInteger();
        List<String> warnings = new ArrayList<>();

        int delay = SwingAnimationFrameRateResolver.resolveFrameDelayMillis(
                true,
                "invalid",
                "invalid",
                () -> {
                    detectionCount.incrementAndGet();
                    return 165;
                },
                warnings::add);

        assertAll(
                () -> assertEquals(16, delay),
                () -> assertEquals(0, detectionCount.get()),
                () -> assertTrue(warnings.isEmpty()));
    }

    /// A failed AWT display query degrades to the default delay and records one warning.
    @Test
    public void displayDetectionFailureUsesDefaultDelay() {
        List<String> warnings = new ArrayList<>();

        int delay = SwingAnimationFrameRateResolver.resolveFrameDelayMillis(
                false,
                null,
                null,
                () -> {
                    throw new IllegalStateException("display unavailable");
                },
                warnings::add);

        assertAll(
                () -> assertEquals(16, delay),
                () -> assertEquals(1, warnings.size()),
                () -> assertTrue(warnings.get(0).contains("display unavailable")));
    }

    /// Resolves one automatic refresh-rate sample without explicit overrides.
    ///
    /// @param refreshRate display refresh rate in hertz
    /// @return resolved Swing timer delay
    private static int resolveAutomatically(int refreshRate) {
        return SwingAnimationFrameRateResolver.resolveFrameDelayMillis(
                false,
                null,
                null,
                () -> refreshRate,
                ignored -> { });
    }
}
