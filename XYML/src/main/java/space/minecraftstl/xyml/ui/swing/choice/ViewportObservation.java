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

import java.time.Duration;
import java.util.OptionalInt;
import java.util.Set;

/// Captures the measured state used to plan a viewport-backed load.
///
/// @param firstVisibleIndex the first row that intersects the viewport
/// @param leadingClipPixels the hidden height of the first visible row
/// @param viewportHeightPixels the measured viewport height
/// @param measuredRowHeightPixels the measured renderer row height
/// @param scrollOffsetPixels the current vertical scroll offset
/// @param previousScrollOffsetPixels the vertical scroll offset from the previous observation
/// @param elapsedSincePrevious the time elapsed since the previous observation
/// @param observedLoadLatency the observed mean data-source load latency
/// @param exactItemCount the exact data-source size, or empty when the end is not known
/// @param pinnedIndices selected or focused indexes that must remain available
@NotNullByDefault
public record ViewportObservation(
        int firstVisibleIndex,
        int leadingClipPixels,
        int viewportHeightPixels,
        int measuredRowHeightPixels,
        int scrollOffsetPixels,
        int previousScrollOffsetPixels,
        Duration elapsedSincePrevious,
        Duration observedLoadLatency,
        OptionalInt exactItemCount,
        @Unmodifiable Set<Integer> pinnedIndices) {
    /// Validates measurements and makes the pinned-index set immutable.
    public ViewportObservation {
        if (firstVisibleIndex < 0) {
            throw new IllegalArgumentException("firstVisibleIndex must not be negative");
        }
        if (leadingClipPixels < 0 || leadingClipPixels >= measuredRowHeightPixels) {
            throw new IllegalArgumentException("leadingClipPixels must be within the first visible row");
        }
        if (viewportHeightPixels <= 0) {
            throw new IllegalArgumentException("viewportHeightPixels must be positive");
        }
        if (measuredRowHeightPixels <= 0) {
            throw new IllegalArgumentException("measuredRowHeightPixels must be positive");
        }
        if (scrollOffsetPixels < 0 || previousScrollOffsetPixels < 0) {
            throw new IllegalArgumentException("scroll offsets must not be negative");
        }
        if (elapsedSincePrevious.isNegative()) {
            throw new IllegalArgumentException("elapsedSincePrevious must not be negative");
        }
        if (observedLoadLatency.isNegative()) {
            throw new IllegalArgumentException("observedLoadLatency must not be negative");
        }
        if (exactItemCount.isPresent() && exactItemCount.getAsInt() < 0) {
            throw new IllegalArgumentException("exactItemCount must not be negative");
        }
        pinnedIndices = Set.copyOf(pinnedIndices);
    }
}
