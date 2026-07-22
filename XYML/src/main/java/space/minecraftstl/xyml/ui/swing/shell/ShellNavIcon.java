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

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.Objects;

/// Paints compact, resolution-independent navigation symbols with the active component color.
@NotNullByDefault
final class ShellNavIcon implements Icon {
    /// Logical square icon size.
    private static final int ICON_SIZE = 20;

    /// Destination represented by this icon.
    private final ShellPageId page;

    /// Creates a symbol for one top-level destination.
    ///
    /// @param page the represented destination
    ShellNavIcon(ShellPageId page) {
        this.page = Objects.requireNonNull(page);
    }

    /// Returns the logical icon width.
    ///
    /// @return the icon width in pixels
    @Override
    public int getIconWidth() {
        return ICON_SIZE;
    }

    /// Returns the logical icon height.
    ///
    /// @return the icon height in pixels
    @Override
    public int getIconHeight() {
        return ICON_SIZE;
    }

    /// Paints the selected destination symbol.
    ///
    /// @param component the owning component, or `null` for standalone painting
    /// @param graphics the target graphics context
    /// @param x the icon's left coordinate
    /// @param y the icon's top coordinate
    @Override
    public void paintIcon(@Nullable Component component, Graphics graphics, int x, int y) {
        Graphics2D target = (Graphics2D) graphics.create();
        try {
            target.translate(x, y);
            target.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            target.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            target.setColor(resolveColor(component));
            target.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            switch (page) {
                case HOME -> paintHome(target);
                case INSTANCES -> paintInstances(target);
                case DOWNLOADS -> paintDownloads(target);
                case ACCOUNTS -> paintAccounts(target);
                case SETTINGS -> paintSettings(target);
            }
        } finally {
            target.dispose();
        }
    }

    /// Resolves a visible foreground for component and standalone painting.
    ///
    /// @param component the optional owning component
    /// @return the foreground color
    private static Color resolveColor(@Nullable Component component) {
        if (component != null && component.getForeground() != null) {
            return component.getForeground();
        }
        @Nullable Color fallback = UIManager.getColor("Label.foreground");
        return fallback == null ? Color.DARK_GRAY : fallback;
    }

    /// Paints a familiar house outline.
    ///
    /// @param graphics the translated icon graphics
    private static void paintHome(Graphics2D graphics) {
        Path2D roof = new Path2D.Double();
        roof.moveTo(2.5, 9.0);
        roof.lineTo(10.0, 2.8);
        roof.lineTo(17.5, 9.0);
        graphics.draw(roof);
        graphics.draw(new Rectangle2D.Double(4.6, 8.2, 10.8, 8.9));
        graphics.draw(new Rectangle2D.Double(8.3, 11.8, 3.4, 5.3));
    }

    /// Paints stacked game-instance tiles.
    ///
    /// @param graphics the translated icon graphics
    private static void paintInstances(Graphics2D graphics) {
        graphics.draw(new Rectangle2D.Double(3.0, 3.0, 14.0, 5.2));
        graphics.draw(new Rectangle2D.Double(3.0, 11.8, 14.0, 5.2));
        graphics.fill(new Ellipse2D.Double(5.0, 5.0, 1.3, 1.3));
        graphics.fill(new Ellipse2D.Double(5.0, 13.8, 1.3, 1.3));
    }

    /// Paints a downward transfer arrow and destination line.
    ///
    /// @param graphics the translated icon graphics
    private static void paintDownloads(Graphics2D graphics) {
        graphics.draw(new Line2D.Double(10.0, 2.5, 10.0, 12.2));
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(5.8, 8.2);
        arrow.lineTo(10.0, 12.5);
        arrow.lineTo(14.2, 8.2);
        graphics.draw(arrow);
        graphics.draw(new Line2D.Double(3.2, 16.5, 16.8, 16.5));
    }

    /// Paints a player silhouette.
    ///
    /// @param graphics the translated icon graphics
    private static void paintAccounts(Graphics2D graphics) {
        graphics.draw(new Ellipse2D.Double(7.0, 2.7, 6.0, 6.0));
        Path2D shoulders = new Path2D.Double();
        shoulders.moveTo(3.5, 17.0);
        shoulders.curveTo(4.1, 12.5, 6.4, 10.8, 10.0, 10.8);
        shoulders.curveTo(13.6, 10.8, 15.9, 12.5, 16.5, 17.0);
        graphics.draw(shoulders);
    }

    /// Paints a compact settings wheel.
    ///
    /// @param graphics the translated icon graphics
    private static void paintSettings(Graphics2D graphics) {
        graphics.draw(new Ellipse2D.Double(6.0, 6.0, 8.0, 8.0));
        graphics.draw(new Ellipse2D.Double(8.5, 8.5, 3.0, 3.0));
        for (int index = 0; index < 8; index++) {
            double angle = Math.PI * index / 4.0;
            double innerX = 10.0 + Math.cos(angle) * 6.0;
            double innerY = 10.0 + Math.sin(angle) * 6.0;
            double outerX = 10.0 + Math.cos(angle) * 8.0;
            double outerY = 10.0 + Math.sin(angle) * 8.0;
            graphics.draw(new Line2D.Double(innerX, innerY, outerX, outerY));
        }
    }
}
