// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.tasks;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JScrollBar;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies exact wheel distances, fractional-pixel ownership, and scrollbar boundary recovery.
@NotNullByDefault
public final class TaskWheelScrollSupportTest {
    /// Keeps both unit and block integer-event multipliers, including the existing zero-amount fallback.
    @Test
    public void preservesIntegerEventDistances() {
        EdtDispatcher.executeAndWait(() -> {
            for (int type : new int[]{MouseWheelEvent.WHEEL_UNIT_SCROLL, MouseWheelEvent.WHEEL_BLOCK_SCROLL}) {
                for (int amount : new int[]{0, 1, 3, 7}) {
                    for (int rotation : new int[]{-2, -1, 1, 2}) {
                        JScrollBar bar = scrollBar();
                        int increment = type == MouseWheelEvent.WHEEL_BLOCK_SCROLL ? 120 : 18;
                        int distance = increment * Math.max(1, Math.abs(amount * rotation));
                        assertTrue(TaskWheelScrollSupport.applyWheel(bar, wheel(bar, type, amount, rotation)));
                        assertEquals(5000 + Integer.signum(rotation) * distance, bar.getValue());
                    }
                }
            }
        });
    }

    /// Multiplies precise unit and block events by the configured scroll amount without rounding each event.
    @Test
    public void preservesFractionalEventMultipliers() {
        EdtDispatcher.executeAndWait(() -> {
            for (int type : new int[]{MouseWheelEvent.WHEEL_UNIT_SCROLL, MouseWheelEvent.WHEEL_BLOCK_SCROLL}) {
                for (int amount : new int[]{0, 1, 3, 7}) {
                    for (int direction : new int[]{-1, 1}) {
                        JScrollBar bar = scrollBar();
                        bar.setBlockIncrement(22);
                        int increment = type == MouseWheelEvent.WHEEL_BLOCK_SCROLL ? 22 : 18;
                        double pixelsPerEvent = 0.125D * Math.max(1, amount) * increment;
                        for (int index = 1; index <= 4; index++) {
                            assertTrue(TaskWheelScrollSupport.applyWheel(bar,
                                    wheel(bar, type, amount, direction * 0.125D)));
                            assertEquals(5000 + direction * (int) (index * pixelsPerEvent), bar.getValue());
                        }
                    }
                }
            }
        });
    }

    /// Retains subpixel input in either direction until a whole pixel is available.
    @Test
    public void accumulatesPositiveAndNegativeSubpixels() {
        EdtDispatcher.executeAndWait(() -> {
            for (int direction : new int[]{-1, 1}) {
                JScrollBar bar = scrollBar();
                bar.setUnitIncrement(1);
                for (int index = 1; index <= 4; index++) {
                    MouseWheelEvent event = wheel(bar, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, direction * 0.25D);
                    assertTrue(TaskWheelScrollSupport.applyWheel(bar, event));
                    assertFalse(event.isConsumed());
                    assertEquals(5000 + direction * (index / 4), bar.getValue());
                }
            }
        });
    }

    /// Retains inward subpixels while the thumb is still at the minimum or maximum.
    @Test
    public void accumulatesSubpixelsWhenLeavingEitherBoundary() {
        EdtDispatcher.executeAndWait(() -> {
            for (int direction : new int[]{-1, 1}) {
                int initialValue = direction > 0 ? 0 : 10;
                JScrollBar bar = new JScrollBar(JScrollBar.VERTICAL, initialValue, 10, 0, 20);
                bar.setUnitIncrement(1);
                for (int index = 1; index <= 4; index++) {
                    assertTrue(apply(bar, direction * 0.25D));
                    assertEquals(initialValue + direction * (index / 4), bar.getValue());
                }
            }
        });
    }

    /// Drops the old direction's remainder before accumulating a reversal.
    @Test
    public void resetsRemainderOnDirectionChange() {
        EdtDispatcher.executeAndWait(() -> {
            for (int direction : new int[]{-1, 1}) {
                JScrollBar bar = scrollBar();
                bar.setUnitIncrement(1);
                assertTrue(apply(bar, direction * 0.75D));
                assertTrue(apply(bar, direction * -0.5D));
                assertEquals(5000, bar.getValue());
                assertTrue(apply(bar, direction * -0.5D));
                assertEquals(5000 - direction, bar.getValue());
            }
        });
    }

    /// Ignores zero rotation without discarding an existing fraction or consuming the event.
    @Test
    public void keepsRemainderAcrossZeroRotation() {
        EdtDispatcher.executeAndWait(() -> {
            JScrollBar bar = scrollBar();
            bar.setUnitIncrement(1);
            assertTrue(apply(bar, 0.75D));
            MouseWheelEvent zero = wheel(bar, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 0.0D);
            assertFalse(TaskWheelScrollSupport.applyWheel(bar, zero));
            assertFalse(zero.isConsumed());
            assertEquals(5000, bar.getValue());
            assertTrue(apply(bar, 0.25D));
            assertEquals(5001, bar.getValue());
        });
    }

    /// Keeps vertical and horizontal scrollbar remainders independent.
    @Test
    public void isolatesRemaindersByScrollbar() {
        EdtDispatcher.executeAndWait(() -> {
            JScrollBar first = scrollBar();
            JScrollBar second = scrollBar();
            second.setOrientation(JScrollBar.HORIZONTAL);
            first.setUnitIncrement(1);
            second.setUnitIncrement(1);
            assertTrue(apply(first, 0.75D));
            assertTrue(apply(second, 0.25D));
            assertEquals(5000, second.getValue());
            assertTrue(apply(first, 0.25D));
            assertEquals(5001, first.getValue());
            assertEquals(5000, second.getValue());
            assertTrue(apply(second, 0.75D));
            assertEquals(5001, second.getValue());
        });
    }

    /// Preserves a fraction when an intervening integer event moves the same scrollbar.
    @Test
    public void retainsFractionAcrossIntegerEvents() {
        EdtDispatcher.executeAndWait(() -> {
            JScrollBar bar = scrollBar();
            bar.setUnitIncrement(1);
            assertTrue(apply(bar, 0.75D));
            assertTrue(apply(bar, 1.0D));
            assertEquals(5001, bar.getValue());
            assertTrue(apply(bar, 0.25D));
            assertEquals(5002, bar.getValue());
        });
    }

    /// Clears a remainder when a scroll reaches either edge, even before another outward event arrives.
    @Test
    public void clearsRemainderWhenReachingBoundary() {
        EdtDispatcher.executeAndWait(() -> {
            for (int direction : new int[]{-1, 1}) {
                JScrollBar bar = new JScrollBar(JScrollBar.VERTICAL, direction > 0 ? 9 : 1, 10, 0, 20);
                bar.setUnitIncrement(1);
                assertTrue(apply(bar, direction * 1.5D));
                assertEquals(direction > 0 ? 10 : 0, bar.getValue());
                bar.setValue(5);
                assertTrue(apply(bar, direction * 0.5D));
                assertEquals(5, bar.getValue());
            }
        });
    }

    /// Clears pending input when an external viewport movement places the scrollbar at an edge.
    @Test
    public void rejectsOutwardInputAndClearsStaleRemainder() {
        EdtDispatcher.executeAndWait(() -> {
            for (int direction : new int[]{-1, 1}) {
                JScrollBar bar = new JScrollBar(JScrollBar.VERTICAL, 5, 10, 0, 20);
                bar.setUnitIncrement(1);
                assertTrue(apply(bar, direction * 0.75D));
                bar.setValue(direction > 0 ? 10 : 0);
                assertFalse(apply(bar, direction * 0.25D));
                bar.setValue(5);
                assertTrue(apply(bar, direction * 0.25D));
                assertEquals(5, bar.getValue());
            }
        });
    }

    /// Clears pending input when the scrollbar no longer overflows.
    @Test
    public void clearsRemainderWithoutOverflow() {
        EdtDispatcher.executeAndWait(() -> {
            JScrollBar bar = new JScrollBar(JScrollBar.VERTICAL, 5, 10, 0, 20);
            bar.setUnitIncrement(1);
            assertTrue(apply(bar, 0.75D));
            bar.setValues(0, 10, 0, 10);
            assertFalse(apply(bar, 0.25D));
            bar.setValues(5, 10, 0, 20);
            assertTrue(apply(bar, 0.25D));
            assertEquals(5, bar.getValue());
        });
    }

    /// Limits huge finite events without overflow and permits immediate movement away from either edge.
    @Test
    public void clampsLargeDeltasAndRecoversInReverse() {
        EdtDispatcher.executeAndWait(() -> {
            for (int direction : new int[]{-1, 1}) {
                JScrollBar bar = new JScrollBar(JScrollBar.VERTICAL, 15, 10, 10, 30);
                bar.setUnitIncrement(18);
                MouseWheelEvent huge = wheel(bar, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3,
                        direction * Double.MAX_VALUE);
                assertTrue(TaskWheelScrollSupport.applyWheel(bar, huge));
                assertEquals(direction > 0 ? 20 : 10, bar.getValue());
                assertFalse(TaskWheelScrollSupport.applyWheel(bar, huge));
                bar.setUnitIncrement(1);
                assertTrue(apply(bar, direction * -0.5D));
                assertTrue(apply(bar, direction * -0.5D));
                assertEquals(direction > 0 ? 19 : 11, bar.getValue());
            }
        });
    }

    /// Ignores nonfinite synthetic input without poisoning the next valid accumulation.
    @Test
    public void ignoresNonfiniteRotation() {
        EdtDispatcher.executeAndWait(() -> {
            for (double rotation : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                JScrollBar bar = scrollBar();
                bar.setUnitIncrement(1);
                assertTrue(apply(bar, 0.75D));
                assertFalse(apply(bar, rotation));
                assertTrue(apply(bar, 0.25D));
                assertEquals(5001, bar.getValue());
            }
        });
    }

    /// Creates a scrollbar with enough room for unbounded-by-layout distance assertions.
    ///
    /// @return test scrollbar with known unit and block increments
    private static JScrollBar scrollBar() {
        JScrollBar bar = new JScrollBar(JScrollBar.VERTICAL, 5000, 100, 0, 10000);
        bar.setUnitIncrement(18);
        bar.setBlockIncrement(120);
        return bar;
    }

    /// Applies one single-unit precise event to a test scrollbar.
    ///
    /// @param bar target scrollbar
    /// @param rotation signed precise rotation
    /// @return whether the scrollbar accepted the event
    private static boolean apply(JScrollBar bar, double rotation) {
        return TaskWheelScrollSupport.applyWheel(bar, wheel(bar, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, rotation));
    }

    /// Creates an event with independently retained integer and precise wheel representations.
    ///
    /// @param bar event source and target
    /// @param type unit or block scrolling
    /// @param amount configured scroll amount
    /// @param rotation signed precise rotation
    /// @return synthetic wheel event
    private static MouseWheelEvent wheel(JScrollBar bar, int type, int amount, double rotation) {
        return new MouseWheelEvent(bar, MouseEvent.MOUSE_WHEEL, 0L, 0,
                4, 4, 4, 4, 0, false, type, amount, (int) rotation, rotation);
    }
}
