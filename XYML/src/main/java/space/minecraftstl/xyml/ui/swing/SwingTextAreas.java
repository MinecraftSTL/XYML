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

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JTextArea;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.util.Objects;

/// Creates read-only text areas that wrap to their allocated width without widening their parent layout.
@NotNullByDefault
public final class SwingTextAreas {
    /// Conservative minimum text width used by details panes before a real viewport width is available.
    private static final int MINIMUM_TEXT_COLUMNS = 16;

    /// Prevents construction of the stateless factory.
    private SwingTextAreas() {
    }

    /// Creates a transparent read-only value area that wraps at word boundaries.
    ///
    /// @return configured wrapping text area
    public static JTextArea wrappingValue() {
        return new WrappingTextArea(true);
    }

    /// Creates a transparent read-only token area that wraps long identifiers and paths at character boundaries.
    ///
    /// @return configured wrapping text area
    public static JTextArea wrappingToken() {
        return new WrappingTextArea(false);
    }

    /// Returns the minimum width required for sixteen wide characters plus component insets.
    ///
    /// @param component component whose font and insets define the width
    /// @return minimum component width
    public static int minimumTextWidth(JComponent component) {
        JComponent target = Objects.requireNonNull(component, "component");
        FontMetrics metrics = target.getFontMetrics(Objects.requireNonNull(target.getFont(), "font"));
        Insets insets = target.getInsets();
        return Math.max(1, metrics.charWidth('W') * MINIMUM_TEXT_COLUMNS)
                + insets.left
                + insets.right;
    }

    /// Returns the largest sixteen-character minimum among the supplied components.
    ///
    /// @param components components participating in one shared layout column
    /// @return largest minimum component width
    public static int maximumMinimumTextWidth(JComponent... components) {
        int maximum = 0;
        for (JComponent component : components) {
            maximum = Math.max(maximum, minimumTextWidth(component));
        }
        return maximum;
    }

    /// Read-only text area whose width follows its parent and whose height follows the resulting wrapped lines.
    @NotNullByDefault
    private static final class WrappingTextArea extends JTextArea {
        /// Creates one borderless transparent wrapping area.
        ///
        /// @param wrapStyleWord whether wrapping prefers word boundaries
        private WrappingTextArea(boolean wrapStyleWord) {
            setEditable(false);
            setLineWrap(true);
            setWrapStyleWord(wrapStyleWord);
            setOpaque(false);
            setFocusable(false);
            setRequestFocusEnabled(false);
            setBorder(BorderFactory.createEmptyBorder());
            setMargin(new Insets(0, 0, 0, 0));
        }

        /// Returns the minimum width required for sixteen representative characters.
        ///
        /// @return minimum component width
        @Override
        public Dimension getMinimumSize() {
            return new Dimension(SwingTextAreas.minimumTextWidth(this), oneLineHeight());
        }

        /// Computes height from the width currently allocated by the surrounding layout.
        ///
        /// @return allocated width and wrapped preferred height
        @Override
        public Dimension getPreferredSize() {
            int width = availableWidth();
            setSize(width, Short.MAX_VALUE);
            Dimension preferred = super.getPreferredSize();
            return new Dimension(width, Math.max(preferred.height, oneLineHeight()));
        }

        /// Allows the parent layout to stretch the component horizontally.
        ///
        /// @return unbounded width and wrapped height
        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        /// Resolves a stable content width before the first real layout pass.
        ///
        /// @return current, parent-derived, or minimum content width
        private int availableWidth() {
            int width = getWidth();
            if (width <= 0 && getParent() != null) {
                Insets insets = getInsets();
                width = getParent().getWidth() - insets.left - insets.right;
            }
            return Math.max(width, SwingTextAreas.minimumTextWidth(this));
        }

        /// Returns one line height including component insets.
        ///
        /// @return minimum component height
        private int oneLineHeight() {
            FontMetrics metrics = getFontMetrics(Objects.requireNonNull(getFont(), "font"));
            Insets insets = getInsets();
            return Math.max(1, metrics.getHeight()) + insets.top + insets.bottom;
        }
    }
}
