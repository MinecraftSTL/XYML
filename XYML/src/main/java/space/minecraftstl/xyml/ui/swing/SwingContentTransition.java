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
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Map;
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
        HORIZONTAL_FORWARD(true, 1),

        /// An earlier destination enters from the left while its predecessor leaves to the right.
        HORIZONTAL_BACKWARD(true, -1),

        /// A later destination enters from below while its predecessor leaves upward.
        VERTICAL_FORWARD(false, 1),

        /// An earlier destination enters from above while its predecessor leaves downward.
        VERTICAL_BACKWARD(false, -1);

        /// Whether movement follows the horizontal rather than vertical axis.
        private final boolean horizontal;

        /// Sign applied to the incoming frame's initial offset on the selected axis.
        private final int incomingOffsetSign;

        /// Creates one stable direction definition.
        ///
        /// @param horizontal whether movement follows the horizontal axis
        /// @param incomingOffsetSign either positive one for forward or negative one for backward
        Direction(boolean horizontal, int incomingOffsetSign) {
            this.horizontal = horizontal;
            this.incomingOffsetSign = incomingOffsetSign;
        }

        /// Returns whether movement follows the horizontal axis.
        ///
        /// @return true for horizontal movement and false for vertical movement
        private boolean isHorizontal() {
            return horizontal;
        }

        /// Returns the incoming frame's initial offset sign.
        ///
        /// @return positive one for forward or negative one for backward
        private int incomingOffsetSign() {
            return incomingOffsetSign;
        }
    }

    /// Client-property key carrying the nearest shared animation context.
    private static final String CONTEXT_PROPERTY = SwingContentTransition.class.getName() + ".context";

    /// EDT-local marker preventing root overlays from being baked into internal transition frames.
    private static final ThreadLocal<Boolean> FRAME_CAPTURE_IN_PROGRESS =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /// Default travel for compact navigation inside one page.
    private static final int DEFAULT_TRAVEL = 12;

    /// Component whose content replacement and transition painting are coordinated.
    private final JComponent host;

    /// Optional direct context used by hosts that already receive explicit animation dependencies.
    private final @Nullable AnimationContext explicitContext;

    /// Maximum travel of either cached frame on the selected axis.
    private final int travel;

    /// Cached visual state from immediately before content replacement.
    private @Nullable BufferedImage outgoingFrame;

    /// Cached visual state from immediately after content replacement.
    private @Nullable BufferedImage incomingFrame;

    /// Host-relative bounds occupied by both cached frames.
    private @Nullable Rectangle frameBounds;

    /// Spatial direction used by the current or most recent transition.
    private Direction direction = Direction.HORIZONTAL_FORWARD;

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
    /// @param travel non-negative maximum travel on the selected axis
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
    /// @param travel non-negative maximum travel on the selected axis
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

    /// Returns whether the EDT is currently painting one internal cached transition frame.
    ///
    /// @return true only during synchronous transition capture
    static boolean isFrameCaptureInProgress() {
        return FRAME_CAPTURE_IN_PROGRESS.get();
    }

    /// Replaces content with the default forward spatial direction.
    ///
    /// @param outgoingContent currently visible content to capture, or null before initial display
    /// @param contentChange synchronous content replacement action
    public void transitionFrom(@Nullable JComponent outgoingContent, Runnable contentChange) {
        transitionFrom(outgoingContent, Direction.HORIZONTAL_FORWARD, contentChange);
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
            double outgoingOffset = -sign * travel * progress;
            double incomingOffset = sign * travel * (1.0 - progress);
            double outgoingX = direction.isHorizontal() ? outgoingOffset : 0.0;
            double outgoingY = direction.isHorizontal() ? 0.0 : outgoingOffset;
            double incomingX = direction.isHorizontal() ? incomingOffset : 0.0;
            double incomingY = direction.isHorizontal() ? 0.0 : incomingOffset;
            drawFrame(
                    transitionGraphics,
                    outgoing,
                    bounds,
                    outgoingX,
                    outgoingY,
                    1.0 - progress);
            drawFrame(
                    transitionGraphics,
                    incoming,
                    bounds,
                    incomingX,
                    incomingY,
                    progress);
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
    public Direction direction() {
        return direction;
    }

    /// Returns the content-only bounds currently replaced by cached frames.
    ///
    /// @return defensive bounds copy, or null while no transition is active
    @Nullable Rectangle activeFrameBounds() {
        @Nullable Rectangle bounds = frameBounds;
        return bounds == null ? null : new Rectangle(bounds);
    }

    /// Converts a positive logical length to the nearest positive device-pixel length.
    ///
    /// @param logicalLength positive component length in Swing coordinates
    /// @param scale positive finite device scale
    /// @return positive device-pixel length
    static int scaledPixelLength(int logicalLength, double scale) {
        if (logicalLength <= 0) {
            throw new IllegalArgumentException("logicalLength must be positive");
        }
        if (!Double.isFinite(scale) || scale <= 0.0) {
            throw new IllegalArgumentException("scale must be positive and finite");
        }
        return Math.max(1, (int) Math.round(logicalLength * scale));
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
        JComponent captureSurface = resolveCaptureSurface();
        Rectangle captureBounds = captureSurface == host
                ? new Rectangle(bounds)
                : SwingUtilities.convertRectangle(host, bounds, captureSurface);
        @Nullable GraphicsConfiguration configuration = captureSurface.getGraphicsConfiguration();
        AffineTransform deviceTransform = configuration == null
                ? new AffineTransform()
                : configuration.getDefaultTransform();
        double deviceScaleX = positiveScale(deviceTransform.getScaleX());
        double deviceScaleY = positiveScale(deviceTransform.getScaleY());
        int pixelWidth = scaledPixelLength(captureBounds.width, deviceScaleX);
        int pixelHeight = scaledPixelLength(captureBounds.height, deviceScaleY);
        BufferedImage frame = createFrame(
                pixelWidth,
                pixelHeight,
                configuration,
                captureSurface.isOpaque());
        Graphics2D frameGraphics = frame.createGraphics();
        RepaintManager repaintManager = RepaintManager.currentManager(captureSurface);
        boolean doubleBufferingEnabled = repaintManager.isDoubleBufferingEnabled();
        boolean previousCaptureState = FRAME_CAPTURE_IN_PROGRESS.get();
        try {
            FRAME_CAPTURE_IN_PROGRESS.set(Boolean.TRUE);
            repaintManager.setDoubleBufferingEnabled(false);
            applyDesktopFontRenderingHints(frameGraphics);
            frameGraphics.scale(
                    (double) pixelWidth / captureBounds.width,
                    (double) pixelHeight / captureBounds.height);
            frameGraphics.translate(-captureBounds.x, -captureBounds.y);
            captureSurface.paint(frameGraphics);
        } finally {
            FRAME_CAPTURE_IN_PROGRESS.set(previousCaptureState);
            repaintManager.setDoubleBufferingEnabled(doubleBufferingEnabled);
            frameGraphics.dispose();
        }
        return frame;
    }

    /// Allocates a display-compatible translucent frame when graphics configuration is available.
    ///
    /// @param width positive frame width in device pixels
    /// @param height positive frame height in device pixels
    /// @param configuration host graphics configuration, or null before display attachment
    /// @param opaque whether the fully composed capture surface is opaque
    /// @return cached image suitable for repeated alpha composition
    private static BufferedImage createFrame(
            int width,
            int height,
            @Nullable GraphicsConfiguration configuration,
            boolean opaque) {
        int transparency = opaque ? Transparency.OPAQUE : Transparency.TRANSLUCENT;
        return configuration == null
                ? new BufferedImage(
                        width,
                        height,
                        opaque ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB_PRE)
                : configuration.createCompatibleImage(width, height, transparency);
    }

    /// Resolves the nearest context-bearing ancestor whose paint contains the composed page background.
    ///
    /// @return shared animation root, or the host when no inherited context surface exists
    private JComponent resolveCaptureSurface() {
        @Nullable Component cursor = host;
        while (cursor != null) {
            if (cursor instanceof JComponent component
                    && component.getClientProperty(CONTEXT_PROPERTY) instanceof AnimationContext) {
                return component;
            }
            cursor = cursor.getParent();
        }
        return host;
    }

    /// Normalizes one graphics-transform scale for defensive off-screen capture.
    ///
    /// @param scale graphics-configuration transform scale
    /// @return positive finite scale, falling back to one
    private static double positiveScale(double scale) {
        double magnitude = Math.abs(scale);
        return Double.isFinite(magnitude) && magnitude > 0.0 ? magnitude : 1.0;
    }

    /// Applies desktop LCD antialiasing and fractional-metrics hints to an off-screen frame when available.
    ///
    /// @param graphics fresh frame graphics
    private static void applyDesktopFontRenderingHints(Graphics2D graphics) {
        @Nullable Object desktopHints = Toolkit.getDefaultToolkit()
                .getDesktopProperty("awt.font.desktophints");
        if (!(desktopHints instanceof Map<?, ?> hints)) {
            return;
        }
        for (Map.Entry<?, ?> entry : hints.entrySet()) {
            @Nullable Object value = entry.getValue();
            if (entry.getKey() instanceof RenderingHints.Key key && value != null) {
                graphics.setRenderingHint(key, value);
            }
        }
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
    /// @param horizontalOffset current subpixel signed horizontal offset
    /// @param verticalOffset current subpixel signed vertical offset
    /// @param opacity current opacity between zero and one
    private static void drawFrame(
            Graphics2D graphics,
            BufferedImage frame,
            Rectangle bounds,
            double horizontalOffset,
            double verticalOffset,
            double opacity) {
        if (opacity <= 0.0) {
            return;
        }
        Graphics2D frameGraphics = (Graphics2D) graphics.create();
        try {
            float boundedOpacity = (float) Math.max(0.0, Math.min(1.0, opacity));
            frameGraphics.setComposite(AlphaComposite.SrcOver.derive(boundedOpacity));
            frameGraphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            frameGraphics.translate(horizontalOffset, verticalOffset);
            frameGraphics.drawImage(
                    frame,
                    bounds.x,
                    bounds.y,
                    bounds.width,
                    bounds.height,
                    null);
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
        if (direction.isHorizontal()) {
            host.repaint(
                    bounds.x - travel,
                    bounds.y,
                    bounds.width + 2 * travel,
                    bounds.height);
        } else {
            host.repaint(
                    bounds.x,
                    bounds.y - travel,
                    bounds.width,
                    bounds.height + 2 * travel);
        }
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
