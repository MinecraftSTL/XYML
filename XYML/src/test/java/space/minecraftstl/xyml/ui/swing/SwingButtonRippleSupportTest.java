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
package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies click-origin button feedback lifecycle and overlapping press ownership.
@NotNullByDefault
public final class SwingButtonRippleSupportTest {
    /// Expansion reaches the farthest corner from the actual click while opacity fades independently.
    @Test
    public void geometryExpandsFromClickAndFadesOut() {
        Point edgeClick = new Point(0, 20);
        Point centerClick = new Point(50, 20);

        assertAll(
                () -> assertEquals(0.0, SwingButtonRippleSupport.rippleRadius(
                        100, 40, edgeClick, 0.0)),
                () -> assertTrue(SwingButtonRippleSupport.rippleRadius(
                        100, 40, edgeClick, 1.0) > SwingButtonRippleSupport.rippleRadius(
                        100, 40, centerClick, 1.0)),
                () -> assertTrue(SwingButtonRippleSupport.rippleOpacity(0.0)
                        > SwingButtonRippleSupport.rippleOpacity(0.5)),
                () -> assertEquals(0.0F, SwingButtonRippleSupport.rippleOpacity(1.0)));
    }

    /// Two presses on one button retain separate animations until global policy completes both.
    @Test
    public void repeatedPressesAnimateConcurrently() {
        SwingAnimator animator = new SwingAnimator(MotionPolicy.FULL, 10_000);

        EdtDispatcher.executeAndWait(() -> {
            JPanel root = new JPanel(null);
            JButton button = new JButton("Launch");
            root.setSize(320, 180);
            button.setBounds(40, 50, 160, 48);
            root.add(button);
            SwingButtonRippleSupport support = new SwingButtonRippleSupport(
                    root,
                    animator,
                    Duration.ofSeconds(2L));

            dispatchPress(button, 12, 18);
            dispatchPress(button, 140, 24);

            BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics graphics = image.getGraphics();
            try {
                support.paintRipples(graphics);
            } finally {
                graphics.dispose();
            }
            assertEquals(2, support.activeRippleCount());

            animator.setMotionPolicy(MotionPolicy.OFF);
            assertAll(
                    () -> assertEquals(0, support.activeRippleCount()),
                    () -> assertTrue(button.getMouseListeners().length > 0));
            support.close();
        });
    }

    /// Infinite speed suppresses ripple retention just like every other decorative transition.
    @Test
    public void infiniteSpeedMakesButtonFeedbackInstant() {
        SwingAnimator animator = new SwingAnimator(MotionPolicy.FULL, 10_000, 200);

        EdtDispatcher.executeAndWait(() -> {
            JPanel root = new JPanel(null);
            JButton button = new JButton("Launch");
            root.add(button);
            SwingButtonRippleSupport support = new SwingButtonRippleSupport(
                    root,
                    animator,
                    Duration.ofSeconds(2L));

            dispatchPress(button, 4, 4);

            assertEquals(0, support.activeRippleCount());
            support.close();
        });
    }

    /// Delivers one primary-button press at caller-selected local coordinates.
    ///
    /// @param button target button
    /// @param x button-local horizontal coordinate
    /// @param y button-local vertical coordinate
    private static void dispatchPress(JButton button, int x, int y) {
        button.dispatchEvent(new MouseEvent(
                button,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                MouseEvent.BUTTON1_DOWN_MASK,
                x,
                y,
                1,
                false,
                MouseEvent.BUTTON1));
    }
}
