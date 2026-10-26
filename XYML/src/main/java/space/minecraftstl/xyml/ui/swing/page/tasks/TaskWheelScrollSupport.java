// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.tasks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.JScrollBar;
import java.awt.event.MouseWheelEvent;

/// Preserves fractional pixel deltas for task-page wheel forwarding on the Swing event dispatch thread.
@NotNullByDefault
final class TaskWheelScrollSupport {
    /// Private client-property key keeping each scrollbar's remainder with its own lifecycle.
    private static final Object PIXEL_REMAINDER_KEY = new Object();

    /// Prevents instantiation of this wheel-forwarding utility.
    private TaskWheelScrollSupport() {
    }

    /// Applies one wheel delta without losing fractional pixels or changing integer-event scroll distances.
    ///
    /// A movable scrollbar accepts subpixel input even before it produces an integer displacement. An event that
    /// reaches an edge is accepted, but its excess and fractional remainder are discarded. Further input toward
    /// that edge is rejected so the caller may forward it to an enclosing viewport.
    ///
    /// @param scrollBar scrollbar receiving the delta
    /// @param event wheel event whose consumption remains the caller's responsibility
    /// @return whether this scrollbar accepted the event, including a retained subpixel delta
    static boolean applyWheel(JScrollBar scrollBar, MouseWheelEvent event) {
        double rotation = event.getPreciseWheelRotation();
        if (rotation == 0.0D || !Double.isFinite(rotation)) {
            return false;
        }
        int direction = rotation > 0.0D ? 1 : -1;
        int minimum = scrollBar.getMinimum();
        int maximum = Math.max(minimum, scrollBar.getMaximum() - scrollBar.getVisibleAmount());
        int previousValue = scrollBar.getValue();
        if ((direction < 0 && previousValue <= minimum) || (direction > 0 && previousValue >= maximum)) {
            scrollBar.putClientProperty(PIXEL_REMAINDER_KEY, null);
            return false;
        }

        int increment = Math.max(1, event.getScrollType() == MouseWheelEvent.WHEEL_BLOCK_SCROLL
                ? scrollBar.getBlockIncrement(direction)
                : scrollBar.getUnitIncrement(direction));
        @Nullable Object storedRemainder = scrollBar.getClientProperty(PIXEL_REMAINDER_KEY);
        double remainder = storedRemainder instanceof Double value ? value : 0.0D;
        if (Math.signum(remainder) != Math.signum(rotation)) {
            remainder = 0.0D;
        }
        double delta;
        if (rotation == event.getWheelRotation()) {
            // Retain the existing integer-event unit and block multipliers, including their minimum fallback.
            int units = Math.max(1, Math.abs(event.getUnitsToScroll()));
            delta = (long) direction * increment * units;
        } else {
            delta = rotation * Math.max(1, event.getScrollAmount()) * increment;
        }
        double accumulated = remainder + delta;
        long wholePixels = (long) accumulated;
        int target = (int) Math.max(minimum, Math.min(maximum, (double) previousValue + wholePixels));
        if (wholePixels != 0L) {
            scrollBar.setValue(target);
        }
        int currentValue = scrollBar.getValue();
        if ((direction < 0 && currentValue <= minimum) || (direction > 0 && currentValue >= maximum)) {
            scrollBar.putClientProperty(PIXEL_REMAINDER_KEY, null);
        } else {
            double remainingPixels = accumulated - wholePixels;
            scrollBar.putClientProperty(PIXEL_REMAINDER_KEY, remainingPixels == 0.0D ? null : remainingPixels);
        }
        return true;
    }
}
