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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.util.Objects;

/// Transparent vertical-scroll content that always follows its viewport width.
@NotNullByDefault
public final class ViewportTrackingPanel extends JPanel implements Scrollable {
    /// Creates a panel using the supplied content layout.
    ///
    /// @param layout content layout manager
    public ViewportTrackingPanel(LayoutManager layout) {
        super(Objects.requireNonNull(layout, "layout"));
        setOpaque(false);
    }

    /// Returns the ordinary preferred size used by the enclosing viewport.
    ///
    /// @return preferred viewport size
    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    /// Returns a stable vertical or horizontal unit increment.
    ///
    /// @param visibleRect current visible rectangle
    /// @param orientation scroll orientation
    /// @param direction scroll direction
    /// @return positive unit increment
    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        Objects.requireNonNull(visibleRect, "visibleRect");
        return orientation == SwingConstants.VERTICAL ? 18 : 12;
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
        int extent = orientation == SwingConstants.VERTICAL
                ? validatedRectangle.height
                : validatedRectangle.width;
        return Math.max(18, extent - 18);
    }

    /// Keeps content width equal to the viewport width.
    ///
    /// @return always true
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    /// Allows content height to exceed the viewport and scroll vertically.
    ///
    /// @return always false
    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
