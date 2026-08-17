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

import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/// Clips one shell popup's single-choice region to the current launcher radius.
@NotNullByDefault
final class RoundedChoicePanel extends JPanel {
    /// Creates one transparent rounded host using the supplied content layout.
    ///
    /// @param layout layout for the list or empty-state content
    RoundedChoicePanel(LayoutManager layout) {
        super(layout);
        configureSurface();
    }

    /// Restores theme colors after a look-and-feel replacement.
    @Override
    public void updateUI() {
        super.updateUI();
        configureSurface();
    }

    /// Paints and clips the complete single-choice region to one rounded outline.
    ///
    /// @param graphics destination graphics
    @Override
    public void paint(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape outline = createOutline();
            copy.clip(outline);
            copy.setColor(getBackground());
            copy.fill(outline);
            super.paint(copy);
            paintRoundedBorder(copy);
        } finally {
            copy.dispose();
        }
    }

    /// Paints the single-choice region's curved boundary.
    ///
    /// @param graphics destination graphics
    @Override
    protected void paintBorder(Graphics graphics) {
        if (cornerRadius() == 0) {
            super.paintBorder(graphics);
        }
    }

    /// Paints the curved boundary after all choice content so it remains visible.
    ///
    /// @param graphics destination graphics
    private void paintRoundedBorder(Graphics2D graphics) {
        int radius = cornerRadius();
        @Nullable Color borderColor = UIManager.getColor("Component.borderColor");
        if (radius == 0 || borderColor == null || getWidth() <= 1 || getHeight() <= 1) {
            return;
        }
        graphics.setColor(borderColor);
        graphics.setStroke(new BasicStroke(1.0F));
        double diameter = Math.min(radius * 2.0, Math.min(getWidth() - 1.0, getHeight() - 1.0));
        graphics.draw(new RoundRectangle2D.Double(
                0.5,
                0.5,
                getWidth() - 1.0,
                getHeight() - 1.0,
                diameter,
                diameter));
    }

    /// Applies the current list background while leaving corners transparent to the popup surface.
    private void configureSurface() {
        setOpaque(false);
        @Nullable Color listBackground = UIManager.getColor("List.background");
        if (listBackground != null) {
            setBackground(listBackground);
        }
    }

    /// Creates the current rectangular or rounded content outline.
    ///
    /// @return exact component outline
    private Shape createOutline() {
        int radius = cornerRadius();
        if (radius == 0) {
            return new Rectangle2D.Double(0.0, 0.0, getWidth(), getHeight());
        }
        double diameter = Math.min(radius * 2.0, Math.min(getWidth(), getHeight()));
        return new RoundRectangle2D.Double(0.0, 0.0, getWidth(), getHeight(), diameter, diameter);
    }

    /// Returns the non-negative radius shared with the containing popup.
    ///
    /// @return current logical radius
    private static int cornerRadius() {
        return Math.max(0, UIManager.getInt("PopupMenu.borderCornerRadius"));
    }
}
