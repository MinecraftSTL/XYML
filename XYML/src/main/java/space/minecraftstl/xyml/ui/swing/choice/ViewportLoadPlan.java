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

import java.util.Set;

/// Describes the visible and adaptively warmed ranges needed by a list viewport.
///
/// @param visibleRange the rows that currently intersect the viewport
/// @param desiredRange visible rows plus measured-capacity warm rows and directional compensation
/// @param pinnedIndices selected or focused indexes outside the desired range
/// @param scrollDirection the direction derived from the latest measurements
/// @param rowsPerSecond the measured absolute scrolling speed
/// @param predictedRowsDuringLoad rows the viewport can traverse during the observed load latency
@NotNullByDefault
public record ViewportLoadPlan(
        IndexRange visibleRange,
        IndexRange desiredRange,
        @Unmodifiable Set<Integer> pinnedIndices,
        ScrollDirection scrollDirection,
        double rowsPerSecond,
        int predictedRowsDuringLoad) {
    /// Validates the plan and makes the pinned-index set immutable.
    public ViewportLoadPlan {
        if (rowsPerSecond < 0.0 || !Double.isFinite(rowsPerSecond)) {
            throw new IllegalArgumentException("rowsPerSecond must be finite and non-negative");
        }
        if (predictedRowsDuringLoad < 0) {
            throw new IllegalArgumentException("predictedRowsDuringLoad must not be negative");
        }
        if (desiredRange.startInclusive() > visibleRange.startInclusive()
                || desiredRange.endExclusive() < visibleRange.endExclusive()) {
            throw new IllegalArgumentException("desiredRange must contain visibleRange");
        }
        pinnedIndices = Set.copyOf(pinnedIndices);
    }

    /// Returns whether this plan asks for the same indexes as another plan.
    ///
    /// Measurements that do not change requested indexes are intentionally ignored so repaint and
    /// layout events cannot repeatedly cancel an in-flight request.
    ///
    /// @param other the plan to compare with
    /// @return whether both plans have identical range and pin demands
    public boolean hasSameDemand(ViewportLoadPlan other) {
        return desiredRange.equals(other.desiredRange) && pinnedIndices.equals(other.pinnedIndices);
    }
}
