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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Verifies Java-side movement for undecorated Swing windows without native title-bar drag handling.
@NotNullByDefault
public final class SwingWindowDragSupportTest {
    /// Drags an inert title-bar surface and verifies the native window follows the pointer.
    @Test
    public void movesWindowFromDraggedSurface() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        EdtDispatcher.executeAndWait(() -> {
            JFrame frame = new JFrame();
            frame.setUndecorated(true);
            try {
                frame.setSize(320, 240);
                frame.setLocation(180, 140);
                JPanel surface = new JPanel();
                frame.setContentPane(surface);
                SwingWindowDragSupport support = new SwingWindowDragSupport(frame, List.of(surface));
                try {
                    dispatchMouse(surface, MouseEvent.MOUSE_PRESSED, InputEvent.BUTTON1_DOWN_MASK,
                            10, 10, 190, 150, 1, MouseEvent.BUTTON1);
                    assertTrue(support.dragging());

                    dispatchMouse(surface, MouseEvent.MOUSE_DRAGGED, InputEvent.BUTTON1_DOWN_MASK,
                            40, 25, 220, 165, 0, MouseEvent.NOBUTTON);
                    assertAll(
                            () -> assertEquals(new Point(210, 155), frame.getLocation()),
                            () -> assertTrue(support.dragging()));

                    dispatchMouse(surface, MouseEvent.MOUSE_RELEASED, 0,
                            40, 25, 220, 165, 1, MouseEvent.BUTTON1);
                    assertFalse(support.dragging());
                } finally {
                    support.close();
                }
            } finally {
                frame.dispose();
            }
        });
    }

    /// Dispatches one synthetic mouse event with matching local and screen coordinates.
    ///
    /// @param component event target
    /// @param id event identifier
    /// @param modifiers active input modifiers
    /// @param x local x coordinate
    /// @param y local y coordinate
    /// @param xOnScreen screen x coordinate
    /// @param yOnScreen screen y coordinate
    /// @param clickCount click count
    /// @param button changed button identifier
    private static void dispatchMouse(
            JPanel component,
            int id,
            int modifiers,
            int x,
            int y,
            int xOnScreen,
            int yOnScreen,
            int clickCount,
            int button) {
        component.dispatchEvent(new MouseEvent(
                component,
                id,
                System.currentTimeMillis(),
                modifiers,
                x,
                y,
                xOnScreen,
                yOnScreen,
                clickCount,
                false,
                button));
    }
}
