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
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.List;
import java.util.Objects;

/// Immutable shell background paint that resolves proportional coordinates against current component bounds.
@NotNullByDefault
sealed interface WindowBackgroundPaint permits WindowBackgroundPaint.Solid,
        WindowBackgroundPaint.Linear, WindowBackgroundPaint.Radial {
    /// Creates the AWT paint for one concrete shell size.
    ///
    /// @param width positive shell width
    /// @param height positive shell height
    /// @return AWT paint ready for the current graphics context
    Paint awtPaint(int width, int height);

    /// Creates a solid background paint.
    ///
    /// @param color exact fill color
    /// @return immutable solid paint
    static WindowBackgroundPaint solid(Color color) {
        return new Solid(color);
    }

    /// One solid AWT color.
    ///
    /// @param color exact fill color
    @NotNullByDefault
    record Solid(Color color) implements WindowBackgroundPaint {
        /// Validates the color.
        public Solid {
            Objects.requireNonNull(color, "color");
        }

        /// Returns the stable AWT color regardless of component bounds.
        @Override
        public Paint awtPaint(int width, int height) {
            return color;
        }
    }

    /// Linear gradient using JavaFX-compatible proportional or absolute coordinates.
    ///
    /// @param startX gradient axis start x coordinate
    /// @param startY gradient axis start y coordinate
    /// @param endX gradient axis end x coordinate
    /// @param endY gradient axis end y coordinate
    /// @param proportional whether coordinates are fractions of shell bounds
    /// @param cycle cycle behavior outside the first and last stop
    /// @param stops normalized nondecreasing gradient stops
    @NotNullByDefault
    record Linear(
            double startX,
            double startY,
            double endX,
            double endY,
            boolean proportional,
            Cycle cycle,
            @Unmodifiable List<GradientStop> stops) implements WindowBackgroundPaint {
        /// Validates and defensively copies the gradient definition.
        public Linear {
            requireFinite(startX, "startX");
            requireFinite(startY, "startY");
            requireFinite(endX, "endX");
            requireFinite(endY, "endY");
            Objects.requireNonNull(cycle, "cycle");
            stops = normalizedStops(stops);
        }

        /// Creates a Java2D linear gradient with JavaFX's zero-length-axis behavior.
        @Override
        public Paint awtPaint(int width, int height) {
            int targetWidth = Math.max(1, width);
            int targetHeight = Math.max(1, height);
            float resolvedStartX = (float) (proportional ? startX * targetWidth : startX);
            float resolvedStartY = (float) (proportional ? startY * targetHeight : startY);
            float resolvedEndX = (float) (proportional ? endX * targetWidth : endX);
            float resolvedEndY = (float) (proportional ? endY * targetHeight : endY);
            WindowGradientArrays gradient = new WindowGradientArrays(stops);
            Color @Unmodifiable [] colors = gradient.colors();
            if (resolvedStartX == resolvedEndX && resolvedStartY == resolvedEndY) {
                return colors[0];
            }
            return new LinearGradientPaint(
                    new Point2D.Float(resolvedStartX, resolvedStartY),
                    new Point2D.Float(resolvedEndX, resolvedEndY),
                    gradient.fractions(),
                    colors,
                    cycle.awtCycle());
        }
    }

    /// Radial gradient using JavaFX-compatible focus, bounds scaling, and cycle semantics.
    ///
    /// @param focusAngleDegrees angle from center to focus in degrees
    /// @param focusDistance radius-relative center-to-focus distance
    /// @param centerX gradient center x coordinate
    /// @param centerY gradient center y coordinate
    /// @param radius positive gradient radius
    /// @param proportional whether center and radius are relative to shell bounds
    /// @param cycle cycle behavior outside the first and last stop
    /// @param stops normalized nondecreasing gradient stops
    @NotNullByDefault
    record Radial(
            double focusAngleDegrees,
            double focusDistance,
            double centerX,
            double centerY,
            double radius,
            boolean proportional,
            Cycle cycle,
            @Unmodifiable List<GradientStop> stops) implements WindowBackgroundPaint {
        /// Validates and defensively copies the gradient definition.
        public Radial {
            requireFinite(focusAngleDegrees, "focusAngleDegrees");
            requireFinite(focusDistance, "focusDistance");
            focusDistance = normalizeFocusDistance(focusDistance);
            requireFinite(centerX, "centerX");
            requireFinite(centerY, "centerY");
            requireFinite(radius, "radius");
            if (radius <= 0.0) {
                throw new IllegalArgumentException("Radial gradient radius must be positive");
            }
            Objects.requireNonNull(cycle, "cycle");
            stops = normalizedStops(stops);
        }

        /// Creates a Java2D radial gradient using JavaFX's proportional ellipse transform.
        @Override
        public Paint awtPaint(int width, int height) {
            float targetWidth = Math.max(1, width);
            float targetHeight = Math.max(1, height);
            float resolvedCenterX = (float) centerX;
            float resolvedCenterY = (float) centerY;
            float resolvedRadius = (float) radius;
            AffineTransform transform = new AffineTransform();
            if (proportional) {
                float dimension = Math.min(targetWidth, targetHeight);
                float boundsCenterX = targetWidth * 0.5f;
                float boundsCenterY = targetHeight * 0.5f;
                resolvedCenterX = boundsCenterX + (resolvedCenterX - 0.5f) * dimension;
                resolvedCenterY = boundsCenterY + (resolvedCenterY - 0.5f) * dimension;
                resolvedRadius *= dimension;
                if (targetWidth != targetHeight) {
                    transform.translate(boundsCenterX, boundsCenterY);
                    transform.scale(targetWidth / dimension, targetHeight / dimension);
                    transform.translate(-boundsCenterX, -boundsCenterY);
                }
            }

            double focusAngle = Math.toRadians(focusAngleDegrees);
            float focusX = (float) (resolvedCenterX
                    + focusDistance * resolvedRadius * Math.cos(focusAngle));
            float focusY = (float) (resolvedCenterY
                    + focusDistance * resolvedRadius * Math.sin(focusAngle));
            WindowGradientArrays gradient = new WindowGradientArrays(stops);
            return new RadialGradientPaint(
                    new Point2D.Float(resolvedCenterX, resolvedCenterY),
                    resolvedRadius,
                    new Point2D.Float(focusX, focusY),
                    gradient.fractions(),
                    gradient.colors(),
                    cycle.awtCycle(),
                    MultipleGradientPaint.ColorSpaceType.SRGB,
                    transform);
        }
    }

    /// Normalized color stop used by both gradient kinds.
    ///
    /// @param offset inclusive zero-to-one gradient offset
    /// @param color exact color at the offset
    @NotNullByDefault
    record GradientStop(double offset, Color color) {
        /// Validates one normalized stop.
        public GradientStop {
            requireFinite(offset, "offset");
            if (offset < 0.0 || offset > 1.0) {
                throw new IllegalArgumentException("Gradient stop offset must be between zero and one");
            }
            Objects.requireNonNull(color, "color");
        }
    }

    /// Gradient repetition mode shared by linear and radial paints.
    @NotNullByDefault
    enum Cycle {
        /// Extends edge colors beyond the defined gradient.
        PAD(MultipleGradientPaint.CycleMethod.NO_CYCLE),

        /// Mirrors every alternate gradient copy.
        REFLECT(MultipleGradientPaint.CycleMethod.REFLECT),

        /// Repeats the gradient from its first stop.
        REPEAT(MultipleGradientPaint.CycleMethod.REPEAT);

        /// Corresponding Java2D cycle method.
        private final MultipleGradientPaint.CycleMethod awtCycle;

        /// Creates one cycle mapping.
        ///
        /// @param awtCycle exact Java2D cycle method
        Cycle(MultipleGradientPaint.CycleMethod awtCycle) {
            this.awtCycle = Objects.requireNonNull(awtCycle, "awtCycle");
        }

        /// Returns the exact Java2D cycle method.
        ///
        /// @return mapped Java2D method
        MultipleGradientPaint.CycleMethod awtCycle() {
            return awtCycle;
        }
    }

    /// Validates one finite numeric gradient property.
    ///
    /// @param value property value
    /// @param name diagnostic property name
    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    /// Clamps radial focus to JavaFX's effective Java2D safety boundary inside the gradient circle.
    ///
    /// @param distance requested radius-relative focus distance
    /// @return signed distance no farther than the JavaFX focus boundary
    private static double normalizeFocusDistance(double distance) {
        double maximum = Math.sqrt(0.99);
        return Math.copySign(Math.min(Math.abs(distance), maximum), distance);
    }

    /// Defensively copies and validates one normalized stop sequence.
    ///
    /// @param stops source stop sequence
    /// @return immutable validated copy
    private static @Unmodifiable List<GradientStop> normalizedStops(List<GradientStop> stops) {
        @Unmodifiable List<GradientStop> copy = List.copyOf(Objects.requireNonNull(stops, "stops"));
        if (copy.size() < 2) {
            throw new IllegalArgumentException("A gradient requires at least two normalized stops");
        }
        double previous = -1.0;
        for (GradientStop stop : copy) {
            if (stop.offset() < previous) {
                throw new IllegalArgumentException("Gradient stops must be nondecreasing");
            }
            previous = stop.offset();
        }
        if (copy.get(0).offset() != 0.0 || copy.get(copy.size() - 1).offset() != 1.0) {
            throw new IllegalArgumentException("Normalized gradient stops must cover zero through one");
        }
        return copy;
    }
}
