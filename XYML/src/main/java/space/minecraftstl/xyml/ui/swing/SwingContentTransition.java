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
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Objects;

/// Animates content replacement from one cached outgoing frame instead of repainting an entire component tree.
///
/// A root context lets nested navigation components reuse the application's animator and duration without adding
/// animation parameters to every page constructor. The outgoing surface is rendered once before replacement; each
/// subsequent frame only composites that image over the live incoming content, keeping EDT frame work bounded.
@NotNullByDefault
public final class SwingContentTransition {
    /// Client-property key carrying the nearest shared animation context.
    private static final String CONTEXT_PROPERTY = SwingContentTransition.class.getName() + ".context";

    /// Default travel for compact navigation inside one page.
    private static final int DEFAULT_TRAVEL = 12;

    /// Component whose content replacement and overlay painting are coordinated.
    private final JComponent host;

    /// Optional direct context used by hosts that already receive explicit animation dependencies.
    private final @Nullable AnimationContext explicitContext;

    /// Maximum leftward travel of the outgoing snapshot.
    private final int travel;

    /// Cached outgoing frame retained only while a transition is active.
    private @Nullable BufferedImage outgoingFrame;

    /// Host-relative bounds occupied by the cached outgoing frame.
    private @Nullable Rectangle outgoingBounds;

    /// Current eased transition progress from zero to one.
    private double progress = 1.0;

    /// Active shared-animation handle, or null while settled.
    private @Nullable AnimationHandle animation;

    /// Creates a nested transition that resolves animation policy from its nearest ancestor context.
    ///
    /// @param host component whose paint method will call [#paintOverlay(Graphics)]
    public SwingContentTransition(JComponent host) {
        this(host, null, DEFAULT_TRAVEL);
    }

    /// Creates an explicitly configured transition for an existing animation owner.
    ///
    /// @param host component whose paint method will call [#paintOverlay(Graphics)]
    /// @param animator shared animation scheduler and policy owner
    /// @param duration non-negative authored transition duration
    /// @param travel non-negative maximum outgoing horizontal travel
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
    /// @param travel non-negative maximum outgoing horizontal travel
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

    /// Replaces content after capturing its outgoing visual state and starts a lightweight overlay transition.
    ///
    /// The change runs exactly once on the EDT. Missing context, disabled decorative motion, zero-sized content,
    /// and zero duration all apply the replacement immediately without allocating an image.
    ///
    /// @param outgoingContent currently visible content to capture, or null before initial display
    /// @param contentChange synchronous content replacement action
    public void transitionFrom(@Nullable JComponent outgoingContent, Runnable contentChange) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(contentChange, "contentChange");
        settle();

        @Nullable AnimationContext context = resolveContext();
        if (outgoingContent == null
                || context == null
                || context.duration().isZero()
                || !context.animator().motionPolicy().allows(MotionPurpose.DECORATIVE)) {
            contentChange.run();
            return;
        }

        @Nullable Rectangle bounds = contentBounds(outgoingContent);
        @Nullable BufferedImage capturedFrame = bounds == null ? null : captureHostRegion(bounds);
        contentChange.run();
        if (capturedFrame == null || bounds == null) {
            return;
        }

        outgoingFrame = capturedFrame;
        outgoingBounds = bounds;
        progress = 0.0;
        AnimationHandle started = context.animator().animate(
                context.duration(),
                MotionPurpose.DECORATIVE,
                Easing.DECELERATE,
                this::applyProgress,
                this::finish);
        animation = started.isRunning() ? started : null;
    }

    /// Paints the cached outgoing frame over already-rendered live incoming content.
    ///
    /// @param graphics host graphics after ordinary child painting
    public void paintOverlay(Graphics graphics) {
        Objects.requireNonNull(graphics, "graphics");
        @Nullable BufferedImage frame = outgoingFrame;
        @Nullable Rectangle bounds = outgoingBounds;
        if (frame == null || bounds == null || progress >= 1.0) {
            return;
        }

        Graphics2D overlayGraphics = (Graphics2D) graphics.create();
        try {
            overlayGraphics.clip(bounds);
            int offset = (int) Math.round(-travel * progress);
            overlayGraphics.translate(bounds.x + offset, bounds.y);
            float opacity = (float) Math.max(0.0, Math.min(1.0, 1.0 - progress));
            overlayGraphics.setComposite(AlphaComposite.SrcOver.derive(opacity));
            overlayGraphics.drawImage(frame, 0, 0, null);
        } finally {
            overlayGraphics.dispose();
        }
    }

    /// Returns whether a cached frame is currently advancing on the shared animator.
    ///
    /// @return true while a content transition remains active
    public boolean isRunning() {
        @Nullable AnimationHandle current = animation;
        return current != null && current.isRunning();
    }

    /// Cancels any active frame delivery and releases the cached image immediately.
    public void settle() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable AnimationHandle current = animation;
        if (current != null) {
            current.cancel();
        }
        clearFrame();
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

    /// Captures one host region before content replacement so transparent children retain their composed surface.
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
                ? new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                : configuration.createCompatibleImage(width, height, Transparency.TRANSLUCENT);
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

    /// Applies one eased progress value and repaints only the moving overlay region.
    ///
    /// @param value eased progress between zero and one
    private void applyProgress(double value) {
        progress = Math.max(0.0, Math.min(1.0, value));
        repaintOverlayBounds();
    }

    /// Releases the completed frame after the incoming live component is fully exposed.
    private void finish() {
        clearFrame();
    }

    /// Clears retained frame state and repaints its former bounds.
    private void clearFrame() {
        @Nullable Rectangle previousBounds = outgoingBounds;
        outgoingFrame = null;
        outgoingBounds = null;
        animation = null;
        progress = 1.0;
        if (previousBounds != null) {
            host.repaint(
                    previousBounds.x - travel,
                    previousBounds.y,
                    previousBounds.width + travel,
                    previousBounds.height);
        }
    }

    /// Repaints the exact union of the original and translated outgoing frame.
    private void repaintOverlayBounds() {
        @Nullable Rectangle bounds = outgoingBounds;
        if (bounds != null) {
            host.repaint(bounds.x - travel, bounds.y, bounds.width + travel, bounds.height);
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
