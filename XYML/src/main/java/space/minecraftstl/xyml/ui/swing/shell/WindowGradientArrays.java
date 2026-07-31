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
