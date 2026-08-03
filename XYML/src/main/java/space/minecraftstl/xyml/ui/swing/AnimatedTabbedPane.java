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
        SwingContentTransition.Direction direction = index > previousIndex
                ? SwingContentTransition.Direction.FORWARD
                : SwingContentTransition.Direction.BACKWARD;
        contentTransition.transitionFrom(outgoing, direction, () -> selectImmediately(index));
    }

    /// Paints cached tab frames during a transition and otherwise delegates to standard child painting.
    ///
    /// @param graphics tab host graphics
    @Override
    protected void paintChildren(Graphics graphics) {
        if (!contentTransition.paintFrames(graphics)) {
            super.paintChildren(graphics);
        }
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

    /// Delegates selection to the standard tab implementation without recursively capturing another frame.
    ///
    /// @param index selected tab index
    private void selectImmediately(int index) {
        super.setSelectedIndex(index);
    }
}
