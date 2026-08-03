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
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Objects;

/// Animates content replacement by composing cached frames from both sides of the replacement.
///
/// A root context lets nested navigation components reuse the application's animator and duration without adding
/// animation parameters to every page constructor. Both visual surfaces are rendered exactly once around the content
/// change. During animation the host paints only these cached frames, so the incoming live tree cannot appear before
/// the transition starts and complex descendants do not consume work on every frame.
@NotNullByDefault
public final class SwingContentTransition {
    /// Spatial direction of one content replacement.
    @NotNullByDefault
    public enum Direction {
        /// A later destination enters from the right while its predecessor leaves to the left.
        FORWARD(1),

        /// An earlier destination enters from the left while its predecessor leaves to the right.
        BACKWARD(-1);

        /// Sign applied to the incoming frame's initial horizontal offset.
        private final int incomingOffsetSign;

        /// Creates one stable direction definition.
        ///
        /// @param incomingOffsetSign either positive one for forward or negative one for backward
        Direction(int incomingOffsetSign) {
            this.incomingOffsetSign = incomingOffsetSign;
        }

        /// Returns the incoming frame's initial horizontal offset sign.
        ///
        /// @return positive one for forward or negative one for backward
        private int incomingOffsetSign() {
            return incomingOffsetSign;
        }
    }

    /// Client-property key carrying the nearest shared animation context.
    private static final String CONTEXT_PROPERTY = SwingContentTransition.class.getName() + ".context";

    /// Default travel for compact navigation inside one page.
    private static final int DEFAULT_TRAVEL = 12;

    /// Component whose content replacement and transition painting are coordinated.
    private final JComponent host;

    /// Optional direct context used by hosts that already receive explicit animation dependencies.
    private final @Nullable AnimationContext explicitContext;

    /// Maximum horizontal travel of either cached frame.
    private final int travel;

    /// Cached visual state from immediately before content replacement.
    private @Nullable BufferedImage outgoingFrame;

    /// Cached visual state from immediately after content replacement.
    private @Nullable BufferedImage incomingFrame;

    /// Host-relative bounds occupied by both cached frames.
    private @Nullable Rectangle frameBounds;

    /// Spatial direction used by the current or most recent transition.
    private Direction direction = Direction.FORWARD;

    /// Current eased transition progress from zero to one.
    private double progress = 1.0;

    /// Active shared-animation handle, or null while settled.
    private @Nullable AnimationHandle animation;

    /// Creates a nested transition that resolves animation policy from its nearest ancestor context.
    ///
    /// @param host component whose paint method will call [#paintFrames(Graphics)]
    public SwingContentTransition(JComponent host) {
        this(host, null, DEFAULT_TRAVEL);
    }

    /// Creates an explicitly configured transition for an existing animation owner.
    ///
    /// @param host component whose paint method will call [#paintFrames(Graphics)]
    /// @param animator shared animation scheduler and policy owner
    /// @param duration non-negative authored transition duration
    /// @param travel non-negative maximum horizontal travel
    public SwingContentTransition(
            JComponent host,
            SwingAnimator animator,
            Duration duration,
            int travel) {
        this(host, new AnimationContext(animator, duration), travel);
    }

    /// Validates common host and travel values for dynamic and explicit contexts.
    ///
    /// @param host transition paint host
    /// @param explicitContext direct context, or null for ancestor lookup
    /// @param travel non-negative maximum horizontal travel
    private SwingContentTransition(
            JComponent host,
            @Nullable AnimationContext explicitContext,
            int travel) {
        this.host = Objects.requireNonNull(host, "host");
        this.explicitContext = explicitContext;
        if (travel < 0) {
            throw new IllegalArgumentException("travel must not be negative");
        }
        this.travel = travel;
    }

    /// Publishes the shared animation context inherited by nested transition-aware components.
    ///
    /// @param root stable application component at the context boundary
    /// @param animator shared animation scheduler and policy owner
    /// @param duration non-negative authored duration for nested content transitions
    public static void provideContext(JComponent root, SwingAnimator animator, Duration duration) {
        Objects.requireNonNull(root, "root").putClientProperty(
                CONTEXT_PROPERTY,
                new AnimationContext(animator, duration));
    }

    /// Replaces content with the default forward spatial direction.
    ///
    /// @param outgoingContent currently visible content to capture, or null before initial display
    /// @param contentChange synchronous content replacement action
    public void transitionFrom(@Nullable JComponent outgoingContent, Runnable contentChange) {
        transitionFrom(outgoingContent, Direction.FORWARD, contentChange);
    }

    /// Replaces content after capturing both visual states and starts a lightweight cached-frame transition.
    ///
    /// The change runs exactly once on the EDT. Missing context, disabled decorative motion, instant animation speed,
    /// zero-sized content, and zero duration all apply the replacement immediately without retaining images.
    ///
    /// @param outgoingContent currently visible content to capture, or null before initial display
    /// @param transitionDirection direction derived from the destinations' relative positions
    /// @param contentChange synchronous content replacement action
    public void transitionFrom(
            @Nullable JComponent outgoingContent,
            Direction transitionDirection,
            Runnable contentChange) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(transitionDirection, "transitionDirection");
        Objects.requireNonNull(contentChange, "contentChange");
        settle();

        @Nullable AnimationContext context = resolveContext();
        if (outgoingContent == null
                || context == null
                || context.duration().isZero()
                || context.animator().animationsCompleteImmediately()
                || !context.animator().motionPolicy().allows(MotionPurpose.DECORATIVE)) {
            contentChange.run();
            return;
        }

        @Nullable Rectangle bounds = contentBounds(outgoingContent);
        @Nullable BufferedImage capturedOutgoing = bounds == null ? null : captureHostRegion(bounds);
        contentChange.run();
        if (capturedOutgoing == null || bounds == null) {
            return;
        }

        layoutVisibleTree(host);
        @Nullable BufferedImage capturedIncoming = captureHostRegion(bounds);
        if (capturedIncoming == null) {
            return;
        }

        outgoingFrame = capturedOutgoing;
        incomingFrame = capturedIncoming;
        frameBounds = bounds;
        direction = transitionDirection;
        progress = 0.0;
        AnimationHandle started = context.animator().animate(
                context.duration(),
                MotionPurpose.DECORATIVE,
                Easing.DECELERATE,
                this::applyProgress,
                this::finish);
        animation = started.isRunning() ? started : null;
    }

    /// Paints both cached frames in place of the host's live children while a transition is active.
    ///
    /// The caller must skip ordinary child painting when this method returns true. Keeping live children out of the
    /// active frame prevents the destination from appearing instantly beneath a translating translucent predecessor.
    ///
    /// @param graphics host graphics before ordinary child painting
    /// @return true when cached frames completely handled child painting
    public boolean paintFrames(Graphics graphics) {
        Objects.requireNonNull(graphics, "graphics");
        @Nullable BufferedImage outgoing = outgoingFrame;
        @Nullable BufferedImage incoming = incomingFrame;
        @Nullable Rectangle bounds = frameBounds;
        if (outgoing == null || incoming == null || bounds == null || progress >= 1.0) {
            return false;
        }

        Graphics2D transitionGraphics = (Graphics2D) graphics.create();
        try {
            transitionGraphics.clip(bounds);
            int sign = direction.incomingOffsetSign();
            int outgoingOffset = (int) Math.round(-sign * travel * progress);
            int incomingOffset = (int) Math.round(sign * travel * (1.0 - progress));
            drawFrame(transitionGraphics, outgoing, bounds, outgoingOffset, 1.0 - progress);
            drawFrame(transitionGraphics, incoming, bounds, incomingOffset, progress);
        } finally {
            transitionGraphics.dispose();
        }
        return true;
    }

    /// Returns whether cached frames are currently advancing on the shared animator.
    ///
    /// @return true while a content transition remains active
    public boolean isRunning() {
        @Nullable AnimationHandle current = animation;
        return current != null && current.isRunning();
    }

    /// Cancels any active frame delivery and releases both cached images immediately.
    public void settle() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable AnimationHandle current = animation;
        if (current != null) {
            current.cancel();
        }
        clearFrames();
    }

    /// Returns the spatial direction used by the current or most recent transition.
    ///
    /// @return stable transition direction
    Direction direction() {
        return direction;
    }

    /// Converts one direct or nested content component's bounds into clipped host coordinates.
    ///
    /// @param content outgoing content with a live parent
    /// @return non-empty clipped host bounds, or null when no frame can be captured
    private @Nullable Rectangle contentBounds(JComponent content) {
        if (content.getParent() == null || content.getWidth() <= 0 || content.getHeight() <= 0) {
            return null;
        }
        Rectangle converted = SwingUtilities.convertRectangle(content.getParent(), content.getBounds(), host);
        Rectangle clipped = converted.intersection(new Rectangle(0, 0, host.getWidth(), host.getHeight()));
        return clipped.isEmpty() ? null : clipped;
    }

    /// Captures one host region so transparent descendants retain their already-composed visual state.
    ///
    /// @param bounds non-empty host-relative region
    /// @return rendered frame, or null when host geometry is no longer usable
    private @Nullable BufferedImage captureHostRegion(Rectangle bounds) {
        if (bounds.width <= 0 || bounds.height <= 0) {
            return null;
        }
        BufferedImage frame = createFrame(bounds.width, bounds.height);
        Graphics2D frameGraphics = frame.createGraphics();
        try {
            frameGraphics.translate(-bounds.x, -bounds.y);
            host.printAll(frameGraphics);
        } finally {
            frameGraphics.dispose();
        }
        return frame;
    }

    /// Allocates a display-compatible translucent frame when graphics configuration is available.
    ///
    /// @param width positive frame width
    /// @param height positive frame height
    /// @return transparent image suitable for repeated alpha composition
    private BufferedImage createFrame(int width, int height) {
        @Nullable GraphicsConfiguration configuration = host.getGraphicsConfiguration();
        return configuration == null
                ? new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
                : configuration.createCompatibleImage(width, height, Transparency.TRANSLUCENT);
    }

    /// Lays out only visible incoming branches before their one-time capture.
    ///
    /// @param container current visible layout branch
    private static void layoutVisibleTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child.isVisible() && child instanceof Container nestedContainer) {
                layoutVisibleTree(nestedContainer);
            }
        }
    }

    /// Draws one cached frame at an offset and bounded opacity.
    ///
    /// @param graphics clipped transition graphics
    /// @param frame cached visual state
    /// @param bounds host-relative frame bounds
    /// @param horizontalOffset current signed horizontal offset
    /// @param opacity current opacity between zero and one
    private static void drawFrame(
            Graphics2D graphics,
            BufferedImage frame,
            Rectangle bounds,
            int horizontalOffset,
            double opacity) {
        if (opacity <= 0.0) {
            return;
        }
        Graphics2D frameGraphics = (Graphics2D) graphics.create();
        try {
            float boundedOpacity = (float) Math.max(0.0, Math.min(1.0, opacity));
            frameGraphics.setComposite(AlphaComposite.SrcOver.derive(boundedOpacity));
            frameGraphics.drawImage(frame, bounds.x + horizontalOffset, bounds.y, null);
        } finally {
            frameGraphics.dispose();
        }
    }

    /// Resolves the explicit context or nearest context-bearing Swing ancestor.
    ///
    /// @return shared animation context, or null outside an application animation tree
    private @Nullable AnimationContext resolveContext() {
        if (explicitContext != null) {
            return explicitContext;
        }
        @Nullable Component cursor = host;
        while (cursor != null) {
            if (cursor instanceof JComponent component) {
                @Nullable Object value = component.getClientProperty(CONTEXT_PROPERTY);
                if (value instanceof AnimationContext context) {
                    return context;
                }
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    /// Applies one eased progress value and repaints the complete cached-frame union.
    ///
    /// @param value eased progress between zero and one
    private void applyProgress(double value) {
        progress = Math.max(0.0, Math.min(1.0, value));
        repaintFrameBounds();
    }

    /// Releases completed frames after the incoming live component is ready to take over.
    private void finish() {
        clearFrames();
    }

    /// Clears retained frame state and repaints its former bounds.
    private void clearFrames() {
        @Nullable Rectangle previousBounds = frameBounds;
        outgoingFrame = null;
        incomingFrame = null;
        frameBounds = null;
        animation = null;
        progress = 1.0;
        if (previousBounds != null) {
            repaintFrameBounds(previousBounds);
        }
    }

    /// Repaints the union of both frames and every possible translated position.
    private void repaintFrameBounds() {
        @Nullable Rectangle bounds = frameBounds;
        if (bounds != null) {
            repaintFrameBounds(bounds);
        }
    }

    /// Repaints one complete cached-frame union.
    ///
    /// @param bounds original host-relative frame bounds
    private void repaintFrameBounds(Rectangle bounds) {
        host.repaint(
                bounds.x - travel,
                bounds.y,
                bounds.width + 2 * travel,
                bounds.height);
    }

    /// Immutable shared animator and authored duration inherited by nested content transitions.
    ///
    /// @param animator shared animation scheduler and policy owner
    /// @param duration non-negative authored transition duration
    @NotNullByDefault
    private record AnimationContext(SwingAnimator animator, Duration duration) {
        /// Validates one inherited animation context.
        private AnimationContext {
            Objects.requireNonNull(animator, "animator");
            Objects.requireNonNull(duration, "duration");
            if (duration.isNegative()) {
                throw new IllegalArgumentException("duration must not be negative");
            }
        }
    }
}
