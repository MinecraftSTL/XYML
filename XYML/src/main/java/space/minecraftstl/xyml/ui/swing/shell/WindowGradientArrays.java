// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;

/// Defensive Java2D arrays preserving JavaFX's handling of duplicate gradient fractions.
@NotNullByDefault
final class WindowGradientArrays {
    /// Strictly increasing fractions accepted by Java2D.
    private final float @Unmodifiable [] fractions;

    /// Colors aligned with `fractions`.
    private final Color @Unmodifiable [] colors;

    /// Converts normalized JavaFX-style stops to Java2D-compatible arrays.
    ///
    /// @param stops normalized nondecreasing stop list
    WindowGradientArrays(@Unmodifiable List<WindowBackgroundPaint.GradientStop> stops) {
        int size = stops.size();
        float[] mutableFractions = new float[size];
        Color[] mutableColors = new Color[size];
        boolean needsFix = false;
        float previous = -1.0f;
        for (int index = 0; index < size; index++) {
            WindowBackgroundPaint.GradientStop stop = stops.get(index);
            float fraction = (float) stop.offset();
            needsFix |= fraction <= previous;
            mutableFractions[index] = fraction;
            mutableColors[index] = stop.color();
            previous = fraction;
        }
        int resultSize = needsFix ? fixFractions(mutableFractions, mutableColors) : size;
        fractions = Arrays.copyOf(mutableFractions, resultSize);
        colors = Arrays.copyOf(mutableColors, resultSize);
    }

    /// Returns a defensive fraction array for one Java2D paint constructor.
    ///
    /// @return strictly increasing fractions
    float @Unmodifiable [] fractions() {
        return fractions.clone();
    }

    /// Returns a defensive color array for one Java2D paint constructor.
    ///
    /// @return colors aligned with the returned fractions
    Color @Unmodifiable [] colors() {
        return colors.clone();
    }

    /// Collapses equal JavaFX fractions into adjacent representable float values.
    ///
    /// @param fractions mutable incoming and outgoing fractions
    /// @param colors mutable incoming and outgoing aligned colors
    /// @return retained array length
    private static int fixFractions(float[] fractions, Color[] colors) {
        float previous = fractions[0];
        int inputIndex = 1;
        int outputIndex = 1;
        while (inputIndex < fractions.length) {
            float fraction = fractions[inputIndex];
            Color color = colors[inputIndex++];
            if (fraction <= previous) {
                if (fraction >= 1.0f) {
                    break;
                }
                fraction = previous + Math.ulp(previous);
                while (inputIndex < fractions.length && fractions[inputIndex] <= fraction) {
                    color = colors[inputIndex++];
                }
            }
            fractions[outputIndex] = fraction;
            colors[outputIndex] = color;
            previous = fraction;
            outputIndex++;
        }
        return outputIndex;
    }
}
