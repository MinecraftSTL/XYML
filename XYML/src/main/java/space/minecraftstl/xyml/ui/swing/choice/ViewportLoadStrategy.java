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

import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;

/// Computes adaptive list load windows from current measurements and data-source boundaries.
@NotNullByDefault
public final class ViewportLoadStrategy {
    /// Maximum additional forward-looking viewports allowed for speed-based prefetch.
    ///
    /// Together with the visible viewport and one baseline viewport on each side, this keeps a
    /// request within six measured viewports. The list model may retain one more viewport on each
    /// side as reversal hysteresis, bounding its contiguous cache to eight measured viewports.
    private static final int MAX_DIRECTIONAL_WARM_VIEWPORTS = 3;

    /// Creates a stateless viewport load strategy.
    public ViewportLoadStrategy() {
    }

    /// Builds a load plan for the supplied viewport observation.
    ///
    /// The visible range is calculated from viewport and measured row geometry. One measured
    /// viewport is prefetched on both sides even on first display or while stationary. Scrolling
    /// speed and observed load latency add a bounded forward-looking range in the current
    /// direction. Every window therefore scales with actual visible capacity rather than a fixed
    /// item count.
    ///
    /// @param observation the current measured viewport state
    /// @return the load plan constrained by any known data-source boundary
    public ViewportLoadPlan plan(ViewportObservation observation) {
        OptionalInt exactItemCount = observation.exactItemCount();
        int visibleRows = divideRoundingUp(
                (long) observation.viewportHeightPixels() + observation.leadingClipPixels(),
                observation.measuredRowHeightPixels());
        int visibleEnd = saturatingAdd(observation.firstVisibleIndex(), visibleRows);
        IndexRange visibleRange = new IndexRange(observation.firstVisibleIndex(), visibleEnd);

        long scrollDelta = (long) observation.scrollOffsetPixels() - observation.previousScrollOffsetPixels();
        ScrollDirection direction = scrollDelta < 0
                ? ScrollDirection.UP
                : scrollDelta > 0 ? ScrollDirection.DOWN : ScrollDirection.STATIONARY;
        double rowsPerSecond = calculateRowsPerSecond(scrollDelta, observation);
        int predictedRows = predictRowsDuringLoad(rowsPerSecond, observation.observedLoadLatency());

        int maximumDirectionalRows = saturatingMultiply(
                visibleRows,
                MAX_DIRECTIONAL_WARM_VIEWPORTS);
        int directionalRows = Math.min(predictedRows, maximumDirectionalRows);
        int desiredStart = saturatingSubtract(visibleRange.startInclusive(), visibleRows);
        int desiredEnd = saturatingAdd(visibleRange.endExclusive(), visibleRows);
        if (direction == ScrollDirection.UP) {
            desiredStart = saturatingSubtract(desiredStart, directionalRows);
        } else if (direction == ScrollDirection.DOWN) {
            desiredEnd = saturatingAdd(desiredEnd, directionalRows);
        }

        IndexRange desiredRange = new IndexRange(desiredStart, desiredEnd);
        if (exactItemCount.isPresent()) {
            visibleRange = visibleRange.clampToItemCount(exactItemCount.getAsInt());
            desiredRange = desiredRange.clampToItemCount(exactItemCount.getAsInt());
        }

        @Unmodifiable Set<Integer> pinnedIndices = validPinnedIndices(
                observation.pinnedIndices(), exactItemCount);
        return new ViewportLoadPlan(
                visibleRange,
                desiredRange,
                pinnedIndices,
                direction,
                rowsPerSecond,
                predictedRows);
    }

    /// Calculates an absolute scrolling speed in rows per second.
    ///
    /// @param scrollDelta the signed pixel distance since the previous observation
    /// @param observation the current viewport observation
    /// @return the observed scrolling speed, or zero without an elapsed interval
    private static double calculateRowsPerSecond(long scrollDelta, ViewportObservation observation) {
        long elapsedNanos = observation.elapsedSincePrevious().toNanos();
        if (scrollDelta == 0 || elapsedNanos == 0L) {
            return 0.0;
        }
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        return Math.abs((double) scrollDelta) / observation.measuredRowHeightPixels() / elapsedSeconds;
    }

    /// Predicts how many complete or partial rows scrolling can cross before a load completes.
    ///
    /// @param rowsPerSecond the measured absolute scrolling speed
    /// @param observedLoadLatency the observed data-source load latency
    /// @return the rounded-up predicted row count
    private static int predictRowsDuringLoad(double rowsPerSecond, java.time.Duration observedLoadLatency) {
        double predictedRows = rowsPerSecond * observedLoadLatency.toNanos() / 1_000_000_000.0;
        if (predictedRows >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(predictedRows);
    }

    /// Filters pinned indexes against the non-negative and optional upper boundaries.
    ///
    /// @param pinnedIndices indexes requested by current selection and focus state
    /// @param exactItemCount the known upper boundary, when available
    /// @return immutable valid pinned indexes
    private static @Unmodifiable Set<Integer> validPinnedIndices(
            @Unmodifiable Set<Integer> pinnedIndices,
            OptionalInt exactItemCount) {
        Set<Integer> valid = new HashSet<>();
        for (int index : pinnedIndices) {
            if (index >= 0
                    && index < Integer.MAX_VALUE
                    && (exactItemCount.isEmpty() || index < exactItemCount.getAsInt())) {
                valid.add(index);
            }
        }
        return Set.copyOf(valid);
    }

    /// Divides positive integers and rounds the result upward.
    ///
    /// @param dividend the positive dividend
    /// @param divisor the positive divisor
    /// @return the rounded-up quotient
    private static int divideRoundingUp(long dividend, int divisor) {
        return (int) Math.min(Integer.MAX_VALUE, 1L + (dividend - 1L) / divisor);
    }

    /// Adds non-negative integers without overflowing.
    ///
    /// @param left the first non-negative value
    /// @param right the second non-negative value
    /// @return the sum, limited to the largest integer
    private static int saturatingAdd(int left, int right) {
        long result = (long) left + right;
        return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    /// Multiplies non-negative integers without overflowing.
    ///
    /// @param left the first non-negative value
    /// @param right the second non-negative value
    /// @return the product, limited to the largest integer
    private static int saturatingMultiply(int left, int right) {
        long result = (long) left * right;
        return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    /// Subtracts non-negative integers without underflowing below zero.
    ///
    /// @param left the non-negative minuend
    /// @param right the non-negative subtrahend
    /// @return the difference, limited to zero
    private static int saturatingSubtract(int left, int right) {
        return left <= right ? 0 : left - right;
    }
}
