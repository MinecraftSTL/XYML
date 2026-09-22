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
package space.minecraftstl.xyml.ui.swing.crash;

import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.Objects;

/// Wraps HTML text to the current layout width and reports the resulting preferred height.
@NotNullByDefault
final class WrappingHtmlPane extends JEditorPane {
    /// Width used before the surrounding BoxLayout has assigned a real component width.
    private static final int FALLBACK_WIDTH = 360;

    /// Creates a non-editable wrapping HTML view.
    ///
    /// @param html complete HTML document
    /// @param font display font
    WrappingHtmlPane(String html, Font font) {
        super("text/html", html);
        setEditable(false);
        setOpaque(false);
        setFocusable(false);
        setBorder(BorderFactory.createEmptyBorder());
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setMinimumSize(new Dimension(0, 0));
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        setFont(Objects.requireNonNull(font, "font"));
    }

    /// Computes the wrapped height for the width currently offered by the parent layout.
    ///
    /// @return preferred width and content-dependent height
    @Override
    public Dimension getPreferredSize() {
        int availableWidth = getWidth();
        if (availableWidth <= 0 && getParent() != null) {
            availableWidth = getParent().getWidth();
        }
        if (availableWidth <= 0) {
            availableWidth = FALLBACK_WIDTH;
        }
        setSize(availableWidth, Short.MAX_VALUE);
        Dimension preferred = super.getPreferredSize();
        return new Dimension(availableWidth, preferred.height);
    }

    /// Allows the parent layout to stretch the view across its available width.
    ///
    /// @return preferred height with an unbounded width
    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
