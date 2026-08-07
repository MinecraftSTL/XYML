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
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;

/// Adds inherited, position-aware cached-frame content transitions to ordinary Swing tab selection.
@NotNullByDefault
public final class AnimatedTabbedPane extends JTabbedPane {
    /// Two-frame transition painted in place of live tab content while selection is changing.
    private final SwingContentTransition contentTransition;

    /// Creates a tab host that resolves animation context from its nearest configured ancestor.
    public AnimatedTabbedPane() {
        contentTransition = new SwingContentTransition(this);
    }

    /// Selects one tab while retaining a lightweight visual frame of the previous content.
    ///
    /// @param index selected tab index
    @Override
    public void setSelectedIndex(int index) {
        int previousIndex = getSelectedIndex();
        @Nullable Component previous = getSelectedComponent();
        if (previous == null || index == previousIndex) {
            super.setSelectedIndex(index);
            return;
        }
        @Nullable JComponent outgoing = previous instanceof JComponent component ? component : null;
        SwingContentTransition.Direction direction = transitionDirection(previousIndex, index);
        contentTransition.transitionFrom(outgoing, direction, () -> selectImmediately(index));
    }

    /// Keeps tab labels live while replacing only the selected content region with cached transition frames.
    ///
    /// @param graphics tab host graphics
    @Override
    protected void paintChildren(Graphics graphics) {
        @Nullable Rectangle contentBounds = contentTransition.activeFrameBounds();
        if (contentBounds == null) {
            super.paintChildren(graphics);
            return;
        }

        Graphics2D tabChromeGraphics = (Graphics2D) graphics.create();
        try {
            @Nullable Shape originalClip = tabChromeGraphics.getClip();
            Area tabChromeClip = originalClip == null
                    ? new Area(new Rectangle(0, 0, getWidth(), getHeight()))
                    : new Area(originalClip);
            tabChromeClip.subtract(new Area(contentBounds));
            tabChromeGraphics.setClip(tabChromeClip);
            super.paintChildren(tabChromeGraphics);
        } finally {
            tabChromeGraphics.dispose();
        }
        contentTransition.paintFrames(graphics);
    }

    /// Cancels retained transition frames before the tab host leaves the display hierarchy.
    @Override
    public void removeNotify() {
        contentTransition.settle();
        super.removeNotify();
    }

    /// Returns whether a tab-content transition is currently active.
    ///
    /// @return true while the outgoing tab frame remains visible
    boolean isContentTransitionRunning() {
        return contentTransition.isRunning();
    }

    /// Returns the direction derived for the current or most recent tab selection.
    ///
    /// @return direction matching the selected tab's relative index
    SwingContentTransition.Direction contentTransitionDirection() {
        return contentTransition.direction();
    }

    /// Derives movement axis from tab placement and movement sign from relative tab index.
    ///
    /// @param previousIndex previously selected tab index
    /// @param destinationIndex incoming tab index
    /// @return spatial direction matching the visible tab arrangement
    private SwingContentTransition.Direction transitionDirection(
            int previousIndex,
            int destinationIndex) {
        boolean forward = destinationIndex > previousIndex;
        return switch (getTabPlacement()) {
            case LEFT, RIGHT -> forward
                    ? SwingContentTransition.Direction.VERTICAL_FORWARD
                    : SwingContentTransition.Direction.VERTICAL_BACKWARD;
            default -> forward
                    ? SwingContentTransition.Direction.HORIZONTAL_FORWARD
                    : SwingContentTransition.Direction.HORIZONTAL_BACKWARD;
        };
    }

    /// Delegates selection to the standard tab implementation without recursively capturing another frame.
    ///
    /// @param index selected tab index
    private void selectImmediately(int index) {
        super.setSelectedIndex(index);
    }
}
