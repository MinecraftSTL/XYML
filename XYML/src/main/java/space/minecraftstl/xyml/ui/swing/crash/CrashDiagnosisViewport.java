// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
