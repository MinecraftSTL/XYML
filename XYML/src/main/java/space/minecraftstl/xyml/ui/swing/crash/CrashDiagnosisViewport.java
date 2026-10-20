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

import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.Dimension;
import java.awt.Rectangle;

/// Keeps diagnosis content at the viewport width while allowing vertical scrolling.
@NotNullByDefault
final class CrashDiagnosisViewport extends JPanel implements Scrollable {
    /// Creates an empty diagnosis panel whose width follows its viewport.
    CrashDiagnosisViewport() {
        super();
    }

    /// Returns the panel's own preferred size as its preferred viewport size.
    ///
    /// @return preferred panel size
    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    /// Returns a stable keyboard and wheel unit increment.
    ///
    /// @param visibleRect visible viewport bounds
    /// @param orientation scrollbar orientation
    /// @param direction scroll direction
    /// @return one small unit step in pixels
    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 16;
    }

    /// Returns a block increment based on the visible viewport.
    ///
    /// @param visibleRect visible viewport bounds
    /// @param orientation scrollbar orientation
    /// @param direction scroll direction
    /// @return one viewport-height or viewport-width step
    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL
                ? visibleRect.height
                : visibleRect.width;
    }

    /// Constrains diagnosis content to the current viewport width.
    ///
    /// @return always true
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    /// Keeps the diagnosis content vertically sized to its preferred height.
    ///
    /// @return always false
    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
