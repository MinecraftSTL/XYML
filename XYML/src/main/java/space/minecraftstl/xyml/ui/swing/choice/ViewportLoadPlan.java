// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
