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
import org.jetbrains.annotations.Nullable;

import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Insets;

/// Paints a one-pixel shell divider from the current look-and-feel palette.
@NotNullByDefault
final class ShellSeparatorBorder extends AbstractBorder {
    /// Whether the divider is painted along the bottom edge instead of the right edge.
    private final boolean bottomEdge;

    /// Creates a divider for one shell band edge.
    ///
    /// @param bottomEdge `true` for a bottom divider, `false` for a right divider
    private ShellSeparatorBorder(boolean bottomEdge) {
        this.bottomEdge = bottomEdge;
    }

    /// Creates a bottom-edge divider.
    ///
    /// @return a dynamic bottom divider
    static ShellSeparatorBorder bottom() {
        return new ShellSeparatorBorder(true);
    }

    /// Creates a right-edge divider.
    ///
    /// @return a dynamic right divider
    static ShellSeparatorBorder right() {
        return new ShellSeparatorBorder(false);
    }

    /// Returns the single-pixel inset reserved by this divider.
    ///
    /// @param component the bordered component
    /// @return the divider insets
    @Override
    public Insets getBorderInsets(Component component) {
        return bottomEdge ? new Insets(0, 0, 1, 0) : new Insets(0, 0, 0, 1);
    }

    /// Paints the divider with a color resolved during every theme-aware repaint.
    ///
    /// @param component the bordered component
    /// @param graphics the target graphics context
    /// @param x the border's left coordinate
    /// @param y the border's top coordinate
    /// @param width the border width
    /// @param height the border height
    @Override
    public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
        graphics.setColor(separatorColor());
        if (bottomEdge) {
            graphics.drawLine(x, y + height - 1, x + width - 1, y + height - 1);
        } else {
            graphics.drawLine(x + width - 1, y, x + width - 1, y + height - 1);
        }
    }

    /// Resolves a visible separator color for both light and dark themes.
    ///
    /// @return the active separator color
    private static Color separatorColor() {
        @Nullable Color separator = UIManager.getColor("Separator.foreground");
        if (separator != null) {
            return separator;
        }
        @Nullable Color foreground = UIManager.getColor("Label.foreground");
        return foreground == null ? new Color(0x808080) : new Color(
                foreground.getRed(), foreground.getGreen(), foreground.getBlue(), 64);
    }
}
