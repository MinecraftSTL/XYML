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
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies background paint parsing used by settings, theme packs, and fallback rendering.
@NotNullByDefault
public final class SwingWindowBackgroundControllerTest {
    /// CSS hexadecimal alpha is interpreted as trailing alpha rather than Java's leading-alpha integer form.
    @Test
    public void parsesTrailingHexadecimalAlpha() throws IOException {
        Color color = SwingWindowBackgroundController.parseColor("#10203040");
        Color javaFxColor = SwingWindowBackgroundController.parseColor("0x10203040");

        assertAll(
                () -> assertEquals(0x10, color.getRed()),
                () -> assertEquals(0x20, color.getGreen()),
                () -> assertEquals(0x30, color.getBlue()),
                () -> assertEquals(0x40, color.getAlpha()),
                () -> assertEquals(color, javaFxColor));
    }

    /// Short hexadecimal and functional alpha forms are accepted with bounded components.
    @Test
    public void parsesShortAndFunctionalColors() throws IOException {
        assertAll(
                () -> assertEquals(new Color(0xAA, 0xBB, 0xCC, 0xDD),
                        SwingWindowBackgroundController.parseColor("#abcd")),
                () -> assertEquals(new Color(10, 20, 30, 128),
                        SwingWindowBackgroundController.parseColor("rgba(10, 20, 30, 0.5)")),
                () -> assertEquals(new Color(10, 20, 30, 255),
                        SwingWindowBackgroundController.parseColor("rgba(10, 20, 30, 1)")),
                () -> assertEquals(new Color(10, 20, 30),
                        SwingWindowBackgroundController.parseColor("rgb(10, 20, 30)")));
    }

    /// JavaFX linear-gradient serialization retains proportional endpoints, reflect, alpha, and percentage stops.
    @Test
    public void parsesJavaFxLinearGradientSerialization() throws IOException {
        WindowBackgroundPaint.Linear definition = assertInstanceOf(
                WindowBackgroundPaint.Linear.class,
                SwingWindowBackgroundController.parsePaint(
                        "linear-gradient(from 0.0% 0.0% to 100.0% 0.0%, reflect, "
                                + "0xff0000ff 0.0%, 0x0000ff80 100.0%)"));
        LinearGradientPaint paint = assertInstanceOf(
                LinearGradientPaint.class,
                definition.awtPaint(200, 100));

        assertAll(
                () -> assertEquals(WindowBackgroundPaint.Cycle.REFLECT, definition.cycle()),
                () -> assertEquals(0.0, paint.getStartPoint().getX()),
                () -> assertEquals(0.0, paint.getStartPoint().getY()),
                () -> assertEquals(200.0, paint.getEndPoint().getX()),
                () -> assertEquals(0.0, paint.getEndPoint().getY()),
                () -> assertEquals(MultipleGradientPaint.CycleMethod.REFLECT, paint.getCycleMethod()),
                () -> assertArrayEquals(new float[]{0.0f, 1.0f}, paint.getFractions(), 0.0f),
                () -> assertEquals(0x80, paint.getColors()[1].getAlpha()));
    }

    /// JavaFX radial-gradient serialization retains focus, repeat, proportional ellipse scaling, and stops.
    @Test
    public void parsesJavaFxRadialGradientSerialization() throws IOException {
        WindowBackgroundPaint.Radial definition = assertInstanceOf(
                WindowBackgroundPaint.Radial.class,
                SwingWindowBackgroundController.parsePaint(
                        "radial-gradient(focus-angle 0.0deg, focus-distance 20.0% , "
                                + "center 50.0% 50.0%, radius 50.0%, repeat, "
                                + "0xffffffff 0.0%, 0x000000ff 100.0%)"));
        RadialGradientPaint paint = assertInstanceOf(
                RadialGradientPaint.class,
                definition.awtPaint(200, 100));

        assertAll(
                () -> assertEquals(WindowBackgroundPaint.Cycle.REPEAT, definition.cycle()),
                () -> assertEquals(100.0, paint.getCenterPoint().getX()),
                () -> assertEquals(50.0, paint.getCenterPoint().getY()),
                () -> assertEquals(110.0, paint.getFocusPoint().getX()),
                () -> assertEquals(50.0, paint.getFocusPoint().getY()),
                () -> assertEquals(50.0f, paint.getRadius()),
                () -> assertEquals(MultipleGradientPaint.CycleMethod.REPEAT, paint.getCycleMethod()),
                () -> assertEquals(2.0, paint.getTransform().getScaleX()),
                () -> assertEquals(1.0, paint.getTransform().getScaleY()));
    }

    /// An out-of-circle JavaFX focus is normalized before AWT paint construction on the EDT.
    @Test
    public void normalizesOutOfCircleRadialFocus() throws IOException {
        WindowBackgroundPaint.Radial definition = assertInstanceOf(
                WindowBackgroundPaint.Radial.class,
                SwingWindowBackgroundController.parsePaint(
                        "radial-gradient(focus-angle 180deg, focus-distance 250%, "
                                + "center 50% 50%, radius 50%, red, blue)"));
        RadialGradientPaint paint = assertInstanceOf(
                RadialGradientPaint.class,
                definition.awtPaint(100, 100));

        assertAll(
                () -> assertEquals(1, Double.compare(definition.focusDistance(), 0.0)),
                () -> assertEquals(Math.sqrt(0.99), Math.abs(definition.focusDistance())),
                () -> assertEquals(0.0, paint.getFocusPoint().getX(), 0.3),
                () -> assertEquals(50.0, paint.getFocusPoint().getY(), 0.01));
    }

    /// Explicit pad and omitted stop positions use JavaFX defaults without requiring the old runtime.
    @Test
    public void parsesPadAndDistributesOmittedStops() throws IOException {
        WindowBackgroundPaint.Linear definition = assertInstanceOf(
                WindowBackgroundPaint.Linear.class,
                SwingWindowBackgroundController.parsePaint(
                        "linear-gradient(to right, pad, red, green 50%, blue)"));

        assertAll(
                () -> assertEquals(WindowBackgroundPaint.Cycle.PAD, definition.cycle()),
                () -> assertEquals(3, definition.stops().size()),
                () -> assertEquals(0.0, definition.stops().get(0).offset()),
                () -> assertEquals(0.5, definition.stops().get(1).offset()),
                () -> assertEquals(1.0, definition.stops().get(2).offset()));
    }

    /// Lone horizontal and vertical side directions stay axis-aligned through the component center.
    @Test
    public void keepsLoneSideDirectionsAxisAligned() throws IOException {
        WindowBackgroundPaint.Linear leftDefinition = assertInstanceOf(
                WindowBackgroundPaint.Linear.class,
                SwingWindowBackgroundController.parsePaint("linear-gradient(to left, red, blue)"));
        WindowBackgroundPaint.Linear bottomDefinition = assertInstanceOf(
                WindowBackgroundPaint.Linear.class,
                SwingWindowBackgroundController.parsePaint("linear-gradient(to bottom, red, blue)"));
        LinearGradientPaint left = assertInstanceOf(
                LinearGradientPaint.class,
                leftDefinition.awtPaint(200, 100));
        LinearGradientPaint bottom = assertInstanceOf(
                LinearGradientPaint.class,
                bottomDefinition.awtPaint(200, 100));

        assertAll(
                () -> assertEquals(200.0, left.getStartPoint().getX()),
                () -> assertEquals(50.0, left.getStartPoint().getY()),
                () -> assertEquals(0.0, left.getEndPoint().getX()),
                () -> assertEquals(50.0, left.getEndPoint().getY()),
                () -> assertEquals(100.0, bottom.getStartPoint().getX()),
                () -> assertEquals(0.0, bottom.getStartPoint().getY()),
                () -> assertEquals(100.0, bottom.getEndPoint().getX()),
                () -> assertEquals(100.0, bottom.getEndPoint().getY()));
    }

    /// Equal JavaFX fractions remain renderable as an effectively hard color boundary in Java2D.
    @Test
    public void preservesDuplicateStopBoundary() throws IOException {
        WindowBackgroundPaint.Linear definition = assertInstanceOf(
                WindowBackgroundPaint.Linear.class,
                SwingWindowBackgroundController.parsePaint(
                        "linear-gradient(to right, red 0%, red 50%, blue 50%, blue 100%)"));
        LinearGradientPaint paint = assertInstanceOf(
                LinearGradientPaint.class,
                definition.awtPaint(100, 20));

        float @Unmodifiable [] fractions = paint.getFractions();
        assertAll(
                () -> assertEquals(4, fractions.length),
                () -> assertEquals(0.5f, fractions[1]),
                () -> assertEquals(Math.nextUp(0.5f), fractions[2]));
    }

    /// A gradient remains invalid when a caller explicitly requires a solid color.
    @Test
    public void rejectsUnsupportedPaint() {
        assertThrows(IOException.class, () ->
                SwingWindowBackgroundController.parseColor("linear-gradient(red, blue)"));
    }
}
