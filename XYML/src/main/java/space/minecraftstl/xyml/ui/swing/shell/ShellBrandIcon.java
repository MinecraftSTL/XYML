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
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/// Paints the compact XYML brand mark used in the shell header.
@NotNullByDefault
final class ShellBrandIcon implements Icon {
    /// Logical brand-mark size.
    private static final int ICON_SIZE = 32;

    /// Returns the brand-mark width.
    ///
    /// @return the width in logical pixels
    @Override
    public int getIconWidth() {
        return ICON_SIZE;
    }

    /// Returns the brand-mark height.
    ///
    /// @return the height in logical pixels
    @Override
    public int getIconHeight() {
        return ICON_SIZE;
    }

    /// Paints a rounded accent tile containing the launcher initial.
    ///
    /// @param component the optional owning component
    /// @param graphics the target graphics context
    /// @param x the icon's left coordinate
    /// @param y the icon's top coordinate
    @Override
    public void paintIcon(@Nullable Component component, Graphics graphics, int x, int y) {
        Graphics2D target = (Graphics2D) graphics.create();
        try {
            target.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            @Nullable Color accent = UIManager.getColor("Component.accentColor");
            target.setColor(accent == null ? new Color(0x3478F6) : accent);
            int configuredArc = UIManager.getInt("Component.arc");
            double arc = Math.max(2.0, Math.min(ICON_SIZE, configuredArc));
            target.fill(new RoundRectangle2D.Double(x, y, ICON_SIZE, ICON_SIZE, arc, arc));

            @Nullable Font baseFont = component == null ? UIManager.getFont("Label.font") : component.getFont();
            Font brandFont = (baseFont == null ? new Font(Font.SANS_SERIF, Font.BOLD, 15) : baseFont)
                    .deriveFont(Font.BOLD, 15.0f);
            target.setFont(brandFont);
            target.setColor(Color.WHITE);
            FontMetrics metrics = target.getFontMetrics();
            String mark = "X";
            int textX = x + (ICON_SIZE - metrics.stringWidth(mark)) / 2;
            int textY = y + (ICON_SIZE - metrics.getHeight()) / 2 + metrics.getAscent();
            target.drawString(mark, textX, textY);
        } finally {
            target.dispose();
        }
    }
}
