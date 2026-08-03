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

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Adds Android-style, click-origin ripple feedback to every button below one Swing component root.
///
/// The support owns no button appearance and paints only after the root's ordinary children. Each press starts a
/// separate animation, so repeated presses can overlap without cancelling or restarting earlier ripples. Container
/// listeners keep lazy pages covered while removed component trees release their listeners and active feedback.
@NotNullByDefault
public final class SwingButtonRippleSupport implements AutoCloseable {
    /// Peak alpha applied to the button foreground color.
    private static final float MAXIMUM_OPACITY = 0.22F;

    /// Root receiving overlay painting and bounding all registered buttons.
    private final JComponent root;

    /// Shared application animator controlling timing, motion policy, and speed.
    private final SwingAnimator animator;

    /// Authored duration of one complete ripple expansion and fade.
    private final Duration duration;

    /// Identity-based buttons currently below the root.
    private final Set<AbstractButton> registeredButtons =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /// Identity-based containers carrying the dynamic-child listener.
    private final Set<Container> registeredContainers =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /// Independently advancing ripple instances, including overlapping presses on one button.
    private final List<Ripple> activeRipples = new ArrayList<>();

    /// Shared listener starting one ripple for every primary mouse press.
    private final ButtonPressListener buttonPressListener = new ButtonPressListener();

    /// Shared listener maintaining registration for lazy component-tree changes.
    private final DynamicComponentListener dynamicComponentListener = new DynamicComponentListener();

    /// Prevents new feedback after cleanup begins.
    private boolean closed;

    /// Creates and installs ripple support below one stable root.
    ///
    /// @param root component whose child-paint method calls [#paintRipples(Graphics)]
    /// @param animator shared application animation scheduler
    /// @param duration non-negative authored duration of one ripple
    public SwingButtonRippleSupport(
            JComponent root,
            SwingAnimator animator,
            Duration duration) {
        EdtDispatcher.requireEventDispatchThread();
        this.root = Objects.requireNonNull(root, "root");
        this.animator = Objects.requireNonNull(animator, "animator");
        this.duration = Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        registerTree(root);
    }

    /// Paints every active ripple over ordinary button content without consuming input events.
    ///
    /// @param graphics root graphics after ordinary child painting
    public void paintRipples(Graphics graphics) {
        Objects.requireNonNull(graphics, "graphics");
        if (closed || activeRipples.isEmpty()) {
            return;
        }
        Ripple[] snapshot = activeRipples.toArray(Ripple[]::new);
        for (Ripple ripple : snapshot) {
            ripple.paint(graphics);
        }
    }

    /// Stops all active feedback and detaches every recursively installed listener.
    @Override
    public void close() {
        EdtDispatcher.executeAndWait(this::closeOnEventDispatchThread);
    }

    /// Returns the number of independently active ripples for focused behavior verification.
    ///
    /// @return active ripple count, including multiple presses on one button
    int activeRippleCount() {
        EdtDispatcher.requireEventDispatchThread();
        return activeRipples.size();
    }

    /// Calculates the expanding circle radius needed to cover the farthest button corner.
    ///
    /// @param width positive button width
    /// @param height positive button height
    /// @param origin button-local click position
    /// @param progress bounded expansion progress
    /// @return non-negative current radius
    static double rippleRadius(int width, int height, Point origin, double progress) {
        Point click = Objects.requireNonNull(origin, "origin");
        double farthestX = Math.max(click.x, width - click.x);
        double farthestY = Math.max(click.y, height - click.y);
        return Math.hypot(farthestX, farthestY) * Math.max(0.0, Math.min(1.0, progress));
    }

    /// Calculates the linearly fading alpha applied alongside eased circle expansion.
    ///
    /// @param progress bounded expansion progress
    /// @return opacity from the configured peak down to zero
    static float rippleOpacity(double progress) {
        double boundedProgress = Math.max(0.0, Math.min(1.0, progress));
        return (float) (MAXIMUM_OPACITY * (1.0 - boundedProgress));
    }

    /// Starts one independent ripple from a button-local press point.
    ///
    /// @param button pressed enabled button
    /// @param origin button-local click position
    private void startRipple(AbstractButton button, Point origin) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || !button.isEnabled() || !registeredButtons.contains(button)) {
            return;
        }
        int x = Math.max(0, Math.min(button.getWidth(), origin.x));
        int y = Math.max(0, Math.min(button.getHeight(), origin.y));
        Ripple ripple = new Ripple(button, new Point(x, y), rippleColor(button));
        activeRipples.add(ripple);
        ripple.start();
    }

    /// Registers one component and every currently attached descendant.
    ///
    /// @param component current component-tree root
    private void registerTree(Component component) {
        if (component instanceof AbstractButton button && registeredButtons.add(button)) {
            button.addMouseListener(buttonPressListener);
        }
        if (component instanceof Container container && registeredContainers.add(container)) {
            container.addContainerListener(dynamicComponentListener);
            for (Component child : container.getComponents()) {
                registerTree(child);
            }
        }
    }

    /// Detaches listeners and cancels ripples owned by one removed component tree.
    ///
    /// @param component removed component-tree root
    private void unregisterTree(Component component) {
        if (component instanceof Container container && registeredContainers.remove(container)) {
            container.removeContainerListener(dynamicComponentListener);
            for (Component child : container.getComponents()) {
                unregisterTree(child);
            }
        }
        if (component instanceof AbstractButton button && registeredButtons.remove(button)) {
            button.removeMouseListener(buttonPressListener);
            cancelRipples(button);
        }
    }

    /// Cancels all independent feedback still owned by one removed button.
    ///
    /// @param button removed button
    private void cancelRipples(AbstractButton button) {
        Ripple[] snapshot = activeRipples.toArray(Ripple[]::new);
        for (Ripple ripple : snapshot) {
            if (ripple.button() == button) {
                ripple.cancel();
                activeRipples.remove(ripple);
            }
        }
    }

    /// Resolves an opaque ripple color from the button foreground and current look and feel.
    ///
    /// @param button pressed button
    /// @return opaque color suitable for alpha composition
    private static Color rippleColor(AbstractButton button) {
        @Nullable Color foreground = button.getForeground();
        if (foreground == null) {
            foreground = UIManager.getColor("Button.foreground");
        }
        Color resolved = foreground != null ? foreground : Color.BLACK;
        return new Color(resolved.getRed(), resolved.getGreen(), resolved.getBlue());
    }

    /// Repaints exactly one currently attached button region.
    ///
    /// @param button ripple-owning button
    private void repaintButton(AbstractButton button) {
        @Nullable Container parent = button.getParent();
        if (parent == null || !SwingUtilities.isDescendingFrom(button, root)) {
            return;
        }
        Point location = SwingUtilities.convertPoint(button, 0, 0, root);
        root.repaint(location.x, location.y, button.getWidth(), button.getHeight());
    }

    /// Completes cleanup on the EDT without retaining removed page trees.
    private void closeOnEventDispatchThread() {
        if (closed) {
            return;
        }
        closed = true;
        Ripple[] snapshot = activeRipples.toArray(Ripple[]::new);
        for (Ripple ripple : snapshot) {
            ripple.cancel();
        }
        activeRipples.clear();
        unregisterTree(root);
        registeredButtons.clear();
        registeredContainers.clear();
        root.repaint();
    }

    /// Receives button-local pointer coordinates and starts independent visual feedback.
    @NotNullByDefault
    private final class ButtonPressListener extends MouseAdapter {
        /// Starts feedback immediately on a primary-button press.
        ///
        /// @param event button-local mouse event
        @Override
        public void mousePressed(MouseEvent event) {
            if (SwingUtilities.isLeftMouseButton(event)
                    && event.getComponent() instanceof AbstractButton button) {
                startRipple(button, event.getPoint());
            }
        }
    }

    /// Tracks buttons added to and removed from lazy application pages.
    @NotNullByDefault
    private final class DynamicComponentListener extends ContainerAdapter {
        /// Registers one newly attached subtree.
        ///
        /// @param event component addition event
        @Override
        public void componentAdded(ContainerEvent event) {
            registerTree(event.getChild());
        }

        /// Detaches one removed subtree and its in-flight feedback.
        ///
        /// @param event component removal event
        @Override
        public void componentRemoved(ContainerEvent event) {
            unregisterTree(event.getChild());
        }
    }

    /// Owns one press's independent geometry, timing, and lifecycle.
    @NotNullByDefault
    private final class Ripple {
        /// Button whose bounds clip this feedback.
        private final AbstractButton button;

        /// Button-local point from which the circle expands.
        private final Point origin;

        /// Opaque source color whose alpha fades over time.
        private final Color color;

        /// Current eased expansion progress from zero to one.
        private double progress;

        /// Independent shared-clock handle, or null after completion.
        private @Nullable AnimationHandle animation;

        /// Creates one ripple before it is scheduled.
        ///
        /// @param button pressed button
        /// @param origin clamped button-local press position
        /// @param color opaque ripple source color
        private Ripple(AbstractButton button, Point origin, Color color) {
            this.button = Objects.requireNonNull(button, "button");
            this.origin = new Point(Objects.requireNonNull(origin, "origin"));
            this.color = Objects.requireNonNull(color, "color");
        }

        /// Starts independent frame delivery without cancelling any existing ripple.
        private void start() {
            AnimationHandle started = animator.animate(
                    duration,
                    MotionPurpose.DECORATIVE,
                    Easing.DECELERATE,
                    this::applyProgress,
                    this::finish);
            animation = started.isRunning() ? started : null;
        }

        /// Returns the owning button by identity.
        ///
        /// @return pressed button
        private AbstractButton button() {
            return button;
        }

        /// Applies one expansion frame and requests a bounded repaint.
        ///
        /// @param value eased progress between zero and one
        private void applyProgress(double value) {
            progress = Math.max(0.0, Math.min(1.0, value));
            repaintButton(button);
        }

        /// Paints this ripple when its button remains visible below the root.
        ///
        /// @param graphics root graphics after ordinary child painting
        private void paint(Graphics graphics) {
            @Nullable Container parent = button.getParent();
            if (parent == null
                    || !button.isVisible()
                    || button.getWidth() <= 0
                    || button.getHeight() <= 0
                    || !SwingUtilities.isDescendingFrom(button, root)) {
                return;
            }
            Point location = SwingUtilities.convertPoint(button, 0, 0, root);
            int width = button.getWidth();
            int height = button.getHeight();
            double radius = rippleRadius(width, height, origin, progress);
            float opacity = rippleOpacity(progress);
            int arc = Math.max(0, Math.min(Math.min(width, height), UIManager.getInt("Button.arc")));

            Graphics2D rippleGraphics = (Graphics2D) graphics.create();
            try {
                rippleGraphics.clip(new RoundRectangle2D.Double(
                        location.x,
                        location.y,
                        width,
                        height,
                        arc,
                        arc));
                rippleGraphics.setComposite(AlphaComposite.SrcOver.derive(opacity));
                rippleGraphics.setColor(color);
                double diameter = radius * 2.0;
                rippleGraphics.fill(new Ellipse2D.Double(
                        location.x + origin.x - radius,
                        location.y + origin.y - radius,
                        diameter,
                        diameter));
            } finally {
                rippleGraphics.dispose();
            }
        }

        /// Removes this completed ripple while preserving every overlapping sibling.
        private void finish() {
            animation = null;
            activeRipples.remove(this);
            repaintButton(button);
        }

        /// Cancels this ripple without affecting other presses.
        private void cancel() {
            @Nullable AnimationHandle current = animation;
            if (current != null) {
                current.cancel();
            }
            animation = null;
            repaintButton(button);
        }
    }
}
