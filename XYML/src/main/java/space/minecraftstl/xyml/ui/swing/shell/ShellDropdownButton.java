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

import javax.swing.JButton;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/// Renders one selected shell value and a trailing disclosure chevron inside a single rounded button.
///
/// A single component lets FlatLaf's caller-selected `Button.arc` own both ends of the control. The chevron is
/// painted rather than hosted in a second button, so theme radius changes cannot leave a square trailing segment.
@NotNullByDefault
final class ShellDropdownButton extends JButton {
    /// Horizontal space reserved for the disclosure chevron.
    private static final int DISCLOSURE_WIDTH = 22;

    /// Creates one left-aligned popup button.
    ShellDropdownButton() {
        setHorizontalAlignment(LEFT);
        setMargin(new Insets(4, 10, 4, 8 + DISCLOSURE_WIDTH));
    }

    /// Paints the standard button before adding a theme-colored trailing chevron.
    ///
    /// @param graphics destination graphics
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            copy.setColor(getForeground());
            int centerX = getComponentOrientation().isLeftToRight() ? getWidth() - 15 : 15;
            int centerY = getHeight() / 2;
            copy.drawLine(centerX - 4, centerY - 2, centerX, centerY + 2);
            copy.drawLine(centerX, centerY + 2, centerX + 4, centerY - 2);
        } finally {
            copy.dispose();
        }
    }
}
