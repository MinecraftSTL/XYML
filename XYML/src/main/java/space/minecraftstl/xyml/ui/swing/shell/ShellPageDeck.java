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
import space.minecraftstl.xyml.ui.swing.AnimationHandle;
import space.minecraftstl.xyml.ui.swing.Easing;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.MotionPurpose;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.AlphaComposite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.time.Duration;
import java.util.Objects;

/// Keeps page bounds stable while cross-fading and slightly translating a destination change.
@NotNullByDefault
final class ShellPageDeck extends JPanel {
    /// Maximum horizontal travel used to preserve spatial continuity during a transition.
    private static final int TRANSITION_TRAVEL = 20;

    /// Animator that enforces the current motion-accessibility policy.
    private final SwingAnimator animator;

    /// Caller-selected duration used for decorative page changes.
    private final Duration transitionDuration;

    /// The page accepting input and remaining after a transition.
    private @Nullable JComponent currentPage;

    /// The page being removed from the visual transition.
    private @Nullable JComponent outgoingPage;

    /// Active transition handle, or `null` when the deck is settled.
    private @Nullable AnimationHandle transition;

    /// Eased transition progress from zero to one.
    private double transitionProgress = 1.0;

    /// Creates a transparent page deck with caller-defined animation timing.
    ///
    /// @param animator the shared Swing animator
    /// @param transitionDuration the non-negative duration of a page change
    ShellPageDeck(SwingAnimator animator, Duration transitionDuration) {
        this.animator = Objects.requireNonNull(animator);
        this.transitionDuration = Objects.requireNonNull(transitionDuration);
        if (transitionDuration.isNegative()) {
            throw new IllegalArgumentException("transitionDuration must not be negative");
        }
        setLayout(null);
        setOpaque(false);
    }

    /// Shows a page, optionally animating from the previous page under the current motion policy.
    ///
    /// @param page the page to show
    /// @param animate whether a settled existing page should transition visually
    void showPage(JComponent page, boolean animate) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(page);

        settleActiveTransition();
        if (currentPage == page) {
            return;
        }

        @Nullable JComponent previousPage = currentPage;
        currentPage = page;
        attachAtFront(page);
        page.setVisible(true);

        if (previousPage == null || !animate) {
            if (previousPage != null) {
                remove(previousPage);
            }
            outgoingPage = null;
            transitionProgress = 1.0;
            revalidate();
            repaint();
            return;
        }

        outgoingPage = previousPage;
        previousPage.setVisible(true);
        transitionProgress = 0.0;
        revalidate();

        AnimationHandle startedTransition = animator.animate(
                transitionDuration,
                MotionPurpose.DECORATIVE,
                Easing.DECELERATE,
                this::applyTransitionProgress,
                this::finishTransition);
        transition = startedTransition.isRunning() ? startedTransition : null;
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

    /// Paints incoming and outgoing pages without changing their layout bounds.
    ///
    /// @param graphics the target graphics context
    @Override
    protected void paintChildren(Graphics graphics) {
        @Nullable JComponent incoming = currentPage;
        if (incoming == null) {
            return;
        }

        @Nullable JComponent outgoing = outgoingPage;
        if (outgoing == null || transitionProgress >= 1.0) {
            // Normal Swing child painting owns double buffering once the deck is settled. Calling child.paint()
            // from here nests RepaintManager buffers and can leave translated copies of the page on screen.
            super.paintChildren(graphics);
            return;
        }

        int outgoingOffset = (int) Math.round(-TRANSITION_TRAVEL * transitionProgress);
        int incomingOffset = (int) Math.round(TRANSITION_TRAVEL * (1.0 - transitionProgress));
        paintPage(graphics, outgoing, outgoingOffset, (float) (1.0 - transitionProgress));
        paintPage(graphics, incoming, incomingOffset, (float) (0.55 + 0.45 * transitionProgress));
    }

    /// Reports overlapping drawing while two destination pages are transitioning.
    ///
    /// @return always `false` because transition pages overlap
    @Override
    public boolean isOptimizedDrawingEnabled() {
        return false;
    }

    /// Cancels timer delivery and settles the deck before it becomes undisplayable.
    @Override
    public void removeNotify() {
        settleActiveTransition();
        super.removeNotify();
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

    /// Applies one eased transition frame without changing child bounds.
    ///
    /// @param progress eased progress from zero to one
    private void applyTransitionProgress(double progress) {
        transitionProgress = Math.max(0.0, Math.min(1.0, progress));
        repaint();
    }

    /// Removes the outgoing page and leaves the incoming page fully rendered.
    private void finishTransition() {
        @Nullable JComponent finishedPage = outgoingPage;
        if (finishedPage != null) {
            remove(finishedPage);
        }
        outgoingPage = null;
        transition = null;
        transitionProgress = 1.0;
        revalidate();
        repaint();
    }

    /// Cancels any active timer before completing the deck's current visual state.
    private void settleActiveTransition() {
        @Nullable AnimationHandle activeTransition = transition;
        if (activeTransition != null) {
            activeTransition.cancel();
        }
        if (outgoingPage != null) {
            finishTransition();
        } else {
            transition = null;
        }
    }

    /// Paints one child page through an isolated alpha and translation transform.
    ///
    /// @param graphics the deck graphics context
    /// @param page the page to paint
    /// @param horizontalOffset the visual-only horizontal translation
    /// @param opacity the bounded page opacity
    private static void paintPage(Graphics graphics, JComponent page, int horizontalOffset, float opacity) {
        Graphics2D pageGraphics = (Graphics2D) graphics.create();
        try {
            pageGraphics.translate(page.getX() + horizontalOffset, page.getY());
            pageGraphics.clipRect(0, 0, page.getWidth(), page.getHeight());
            pageGraphics.setComposite(AlphaComposite.SrcOver.derive(Math.max(0.0f, Math.min(1.0f, opacity))));
            // printAll bypasses Swing double buffering while the deck composites two translated page frames.
            page.printAll(pageGraphics);
        } finally {
            pageGraphics.dispose();
        }
    }
}
