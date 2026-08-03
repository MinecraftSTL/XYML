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
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingContentTransition;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Graphics;
import java.time.Duration;
import java.util.Objects;

/// Keeps page bounds stable while translating and cross-fading cached frames from both destinations.
@NotNullByDefault
final class ShellPageDeck extends JPanel {
    /// Maximum horizontal travel used to preserve spatial continuity during a transition.
    private static final int TRANSITION_TRAVEL = 20;

    /// Snapshot-composited transition that avoids repainting complete page trees on every frame.
    private final SwingContentTransition contentTransition;

    /// The page accepting input and remaining after a transition.
    private @Nullable JComponent currentPage;

    /// Creates a transparent page deck with caller-defined animation timing.
    ///
    /// @param animator the shared Swing animator
    /// @param transitionDuration the non-negative duration of a page change
    ShellPageDeck(SwingAnimator animator, Duration transitionDuration) {
        Objects.requireNonNull(animator, "animator");
        Objects.requireNonNull(transitionDuration, "transitionDuration");
        if (transitionDuration.isNegative()) {
            throw new IllegalArgumentException("transitionDuration must not be negative");
        }
        setLayout(null);
        setOpaque(false);
        contentTransition = new SwingContentTransition(
                this,
                animator,
                transitionDuration,
                TRANSITION_TRAVEL);
    }

    /// Shows a page, optionally animating from the previous page under the current motion policy.
    ///
    /// @param page the page to show
    /// @param animate whether a settled existing page should transition visually
    void showPage(JComponent page, boolean animate) {
        showPage(page, animate, SwingContentTransition.Direction.FORWARD);
    }

    /// Shows a page using a direction derived from the destinations' visual positions.
    ///
    /// @param page the page to show
    /// @param animate whether a settled existing page should transition visually
    /// @param direction spatial relationship between the previous and incoming pages
    void showPage(
            JComponent page,
            boolean animate,
            SwingContentTransition.Direction direction) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(page);
        Objects.requireNonNull(direction, "direction");

        if (currentPage == page) {
            return;
        }

        @Nullable JComponent previousPage = currentPage;
        if (previousPage == null || !animate) {
            contentTransition.settle();
            replacePage(previousPage, page);
            return;
        }

        contentTransition.transitionFrom(previousPage, direction, () -> replacePage(previousPage, page));
    }

    /// Returns the currently visible destination page.
    ///
    /// @return the active page, or `null` before first navigation
    @Nullable JComponent currentPage() {
        return currentPage;
    }

    /// Sizes every retained transition page to the same stable content bounds.
    @Override
    public void doLayout() {
        for (java.awt.Component child : getComponents()) {
            child.setBounds(0, 0, getWidth(), getHeight());
        }
    }

    /// Paints cached destination frames during a transition and otherwise paints the live page.
    ///
    /// @param graphics the target graphics context
    @Override
    protected void paintChildren(Graphics graphics) {
        if (!contentTransition.paintFrames(graphics)) {
            super.paintChildren(graphics);
        }
    }

    /// Cancels timer delivery and settles the deck before it becomes undisplayable.
    @Override
    public void removeNotify() {
        contentTransition.settle();
        super.removeNotify();
    }

    /// Returns whether the cached outgoing frame is currently transitioning.
    ///
    /// @return true while a top-level page change remains active
    boolean isTransitionRunning() {
        return contentTransition.isRunning();
    }

    /// Adds a cached page or moves it to the input-facing component position.
    ///
    /// @param page the incoming page
    private void attachAtFront(JComponent page) {
        page.setOpaque(false);
        if (page.getParent() != this) {
            add(page, 0);
        } else {
            setComponentZOrder(page, 0);
        }
    }

    /// Replaces the sole live destination while preserving cached page instances for later navigation.
    ///
    /// @param previousPage outgoing cached page, or null before initial display
    /// @param page incoming cached page
    private void replacePage(@Nullable JComponent previousPage, JComponent page) {
        currentPage = page;
        attachAtFront(page);
        page.setVisible(true);
        if (previousPage != null) {
            remove(previousPage);
            previousPage.setVisible(false);
        }
        revalidate();
        repaint();
    }
}
