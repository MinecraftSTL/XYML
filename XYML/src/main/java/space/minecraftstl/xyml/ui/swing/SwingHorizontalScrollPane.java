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
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.Objects;

/// Adds one page-level horizontal scrollbar only when a fixed-width two-column workspace cannot fit.
@NotNullByDefault
public final class SwingHorizontalScrollPane extends JScrollPane {
    /// Scrollable wrapper whose minimum width is the complete two-column workspace width.
    private final HorizontalViewportPanel viewportPanel;

    /// Creates one horizontal-only page workspace.
    ///
    /// @param content complete two-column workspace
    /// @param name stable scroll-pane name
    /// @param minimumContentWidth minimum width required by the complete workspace
    public SwingHorizontalScrollPane(
            JComponent content,
            String name,
            int minimumContentWidth) {
        this(new HorizontalViewportPanel(Objects.requireNonNull(content, "content")));
        setName(Objects.requireNonNull(name, "name"));
        setMinimumContentWidth(minimumContentWidth);
    }

    /// Creates one scroll pane around the prepared viewport content.
    ///
    /// @param viewportPanel prepared scrollable wrapper
    private SwingHorizontalScrollPane(HorizontalViewportPanel viewportPanel) {
        super(viewportPanel);
        this.viewportPanel = viewportPanel;
        setBorder(BorderFactory.createEmptyBorder());
        setOpaque(false);
        getViewport().setOpaque(false);
        setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_AS_NEEDED);
        setVerticalScrollBarPolicy(VERTICAL_SCROLLBAR_NEVER);
        getHorizontalScrollBar().setUnitIncrement(24);
    }

    /// Updates the complete workspace minimum width.
    ///
    /// @param minimumContentWidth minimum width required by both columns and the divider
    public void setMinimumContentWidth(int minimumContentWidth) {
        if (viewportPanel.minimumContentWidth == minimumContentWidth) {
            return;
        }
        viewportPanel.setMinimumContentWidth(minimumContentWidth);
        revalidate();
    }

    /// Horizontal viewport that tracks width when it is wide enough and otherwise exposes the complete workspace width.
    @NotNullByDefault
    private static final class HorizontalViewportPanel extends JPanel implements Scrollable {
        /// Minimum width required by the complete two-column workspace.
        private int minimumContentWidth;

        /// Creates one borderless workspace wrapper.
        ///
        /// @param content complete two-column workspace
        private HorizontalViewportPanel(JComponent content) {
            super(new BorderLayout());
            setOpaque(false);
            add(Objects.requireNonNull(content, "content"), BorderLayout.CENTER);
        }

        /// Updates the required complete workspace width.
        ///
        /// @param width required workspace width
        private void setMinimumContentWidth(int width) {
            minimumContentWidth = Math.max(0, width);
            revalidate();
        }

        /// Returns the initial preferred viewport size.
        ///
        /// @return minimum workspace width and one-pixel height
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(minimumContentWidth, 1);
        }

        /// Returns a stable horizontal or vertical unit increment.
        ///
        /// @param visibleRect current visible rectangle
        /// @param orientation scroll orientation
        /// @param direction scroll direction
        /// @return positive unit increment
        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            Objects.requireNonNull(visibleRect, "visibleRect");
            return orientation == SwingConstants.HORIZONTAL ? 24 : 18;
        }

        /// Returns one viewport-relative block increment.
        ///
        /// @param visibleRect current visible rectangle
        /// @param orientation scroll orientation
        /// @param direction scroll direction
        /// @return positive block increment
        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            Rectangle validatedRectangle = Objects.requireNonNull(visibleRect, "visibleRect");
            int extent = orientation == SwingConstants.HORIZONTAL
                    ? validatedRectangle.width
                    : validatedRectangle.height;
            return Math.max(24, extent - 24);
        }

        /// Tracks the viewport width whenever the complete workspace fits.
        ///
        /// @return whether the wrapper should fill the viewport width
        @Override
        public boolean getScrollableTracksViewportWidth() {
            Component parent = getParent();
            return parent == null || parent.getWidth() >= minimumContentWidth;
        }

        /// Keeps the workspace height synchronized with the viewport.
        ///
        /// @return always true
        @Override
        public boolean getScrollableTracksViewportHeight() {
            return true;
        }

        /// Uses the complete workspace minimum as preferred width instead of exposing child preferred widths.
        ///
        /// @return required complete workspace width and one-pixel height
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(minimumContentWidth, 1);
        }
    }
}
