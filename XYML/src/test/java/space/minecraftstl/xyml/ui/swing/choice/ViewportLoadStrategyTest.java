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
package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests geometry- and observation-driven viewport load planning.
@NotNullByDefault
public final class ViewportLoadStrategyTest {
    /// The stateless strategy under test.
    private final ViewportLoadStrategy strategy = new ViewportLoadStrategy();

    /// Verifies that visible rows and stationary warm ranges derive from measured geometry.
    @Test
    public void derivesVisibleRowsFromActualMeasurements() {
        ViewportLoadPlan clippedRows = strategy.plan(observation(
                10, 8, 100, 24, 240, 240,
                Duration.ofMillis(16), Duration.ZERO, OptionalInt.of(100), Set.of()));
        ViewportLoadPlan tallerViewport = strategy.plan(observation(
                4, 0, 300, 20, 80, 80,
                Duration.ofMillis(16), Duration.ZERO, OptionalInt.of(100), Set.of()));

        assertEquals(new IndexRange(10, 15), clippedRows.visibleRange());
        assertEquals(new IndexRange(5, 20), clippedRows.desiredRange());
        assertEquals(new IndexRange(4, 19), tallerViewport.visibleRange());
        assertEquals(new IndexRange(0, 34), tallerViewport.desiredRange());
    }

    /// Verifies that measured direction, speed, and latency determine directional prefetch.
    @Test
    public void predictsDirectionalRowsFromSpeedAndLatency() {
        ViewportLoadPlan scrollingDown = strategy.plan(observation(
                20, 0, 100, 20, 400, 200,
                Duration.ofMillis(500), Duration.ofMillis(250), OptionalInt.of(100), Set.of()));
        ViewportLoadPlan scrollingUp = strategy.plan(observation(
                20, 0, 100, 20, 100, 300,
                Duration.ofMillis(500), Duration.ofMillis(250), OptionalInt.of(100), Set.of()));

        assertEquals(ScrollDirection.DOWN, scrollingDown.scrollDirection());
        assertEquals(20.0, scrollingDown.rowsPerSecond());
        assertEquals(5, scrollingDown.predictedRowsDuringLoad());
        assertEquals(new IndexRange(15, 35), scrollingDown.desiredRange());

        assertEquals(ScrollDirection.UP, scrollingUp.scrollDirection());
        assertEquals(20.0, scrollingUp.rowsPerSecond());
        assertEquals(5, scrollingUp.predictedRowsDuringLoad());
        assertEquals(new IndexRange(10, 30), scrollingUp.desiredRange());
    }

    /// Verifies that high-speed prediction cannot grow beyond measured viewport-based bounds.
    @Test
    public void capsDirectionalWarmRangeInMeasuredViewportUnits() {
        ViewportLoadPlan plan = strategy.plan(observation(
                20, 0, 100, 20, 1_200, 200,
                Duration.ofMillis(100), Duration.ofSeconds(1), OptionalInt.of(100), Set.of()));

        assertEquals(500, plan.predictedRowsDuringLoad());
        assertEquals(new IndexRange(15, 45), plan.desiredRange());
    }

    /// Verifies that exact source bounds constrain demand and invalid pins are discarded.
    @Test
    public void respectsExactDataSourceBoundary() {
        ViewportLoadPlan plan = strategy.plan(observation(
                47, 0, 100, 20, 940, 840,
                Duration.ofMillis(100), Duration.ofMillis(200), OptionalInt.of(50), Set.of(-1, 3, 49, 50)));

        assertEquals(new IndexRange(47, 50), plan.visibleRange());
        assertEquals(new IndexRange(42, 50), plan.desiredRange());
        assertEquals(Set.of(3, 49), plan.pinnedIndices());
    }

    /// Creates a complete viewport observation for one test case.
    ///
    /// @param firstVisibleIndex the first intersecting logical index
    /// @param leadingClipPixels the clipped pixels in the first row
    /// @param viewportHeightPixels the measured viewport height
    /// @param rowHeightPixels the measured row height
    /// @param scrollOffsetPixels the latest scroll offset
    /// @param previousScrollOffsetPixels the previous scroll offset
    /// @param elapsed the observation interval
    /// @param latency the measured source latency
    /// @param itemCount the optional exact source count
    /// @param pins selected and focused indexes
    /// @return the resulting immutable observation
    private static ViewportObservation observation(
            int firstVisibleIndex,
            int leadingClipPixels,
            int viewportHeightPixels,
            int rowHeightPixels,
            int scrollOffsetPixels,
            int previousScrollOffsetPixels,
            Duration elapsed,
            Duration latency,
            OptionalInt itemCount,
            @Unmodifiable Set<Integer> pins) {
        return new ViewportObservation(
                firstVisibleIndex,
                leadingClipPixels,
                viewportHeightPixels,
                rowHeightPixels,
                scrollOffsetPixels,
                previousScrollOffsetPixels,
                elapsed,
                latency,
                itemCount,
                pins);
    }
}
