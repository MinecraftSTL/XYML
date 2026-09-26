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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.auth.yggdrasil.TextureModel;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.accessibility.AccessibleContext;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferedImage;
import java.util.Objects;

/// Interactive software 3D preview for one decoded Minecraft skin and optional cape.
///
/// Horizontal and vertical mouse dragging rotate the model continuously, while the mouse wheel changes the fitted
/// zoom. The renderer uses Java2D only and includes animated movement cycles plus standing, sneaking, riding, and
/// prone postures.
@NotNullByDefault
final class OfflineSkinPreviewPanel extends JComponent {
    /// Animation repaint interval in milliseconds.
    private static final int ANIMATION_DELAY_MILLIS = 33;

    /// Empty border retained around the projected player.
    private static final int PREVIEW_PADDING = 24;

    /// Smallest accepted wheel zoom.
    private static final double MIN_ZOOM = 0.55;

    /// Largest accepted wheel zoom.
    private static final double MAX_ZOOM = 2.4;

    /// Smallest accepted vertical view rotation.
    private static final double MIN_PITCH = -70.0;

    /// Largest accepted vertical view rotation.
    private static final double MAX_PITCH = 70.0;

    /// Decoded player texture currently rendered, or null for a textual state.
    private @Nullable BufferedImage skinImage;

    /// Decoded cape texture currently rendered, or null when absent.
    private @Nullable BufferedImage capeImage;

    /// Arm width represented by the current texture.
    private TextureModel textureModel = TextureModel.WIDE;

    /// Localized message rendered while no decoded preview is available.
    private String message = " ";

    /// Accumulated horizontal view rotation in degrees.
    private double yawDegrees = 22.5;

    /// Accumulated vertical view rotation in degrees.
    private double pitchDegrees = 10.0;

    /// Wheel zoom factor.
    private double zoomFactor = 1.0;

    /// Selected movement cycle.
    private SkinPreviewMotion motion = SkinPreviewMotion.IDLE;

    /// Selected body posture.
    private SkinPreviewPosture posture = SkinPreviewPosture.STANDING;

    /// Last horizontal drag coordinate, or null while no mouse drag is active.
    private @Nullable Integer dragOriginX;

    /// Last vertical drag coordinate, or null while no mouse drag is active.
    private @Nullable Integer dragOriginY;

    /// Rotation captured at the start of the current drag gesture.
    private double dragOriginYaw;

    /// Vertical rotation captured at the start of the current drag gesture.
    private double dragOriginPitch;

    /// Accumulated animation time in seconds.
    private double animationSeconds;

    /// EDT timer that advances active movement cycles.
    private final Timer animationTimer = new Timer(
            ANIMATION_DELAY_MILLIS,
            event -> {
                animationSeconds += ANIMATION_DELAY_MILLIS / 1000.0;
                repaint();
            });

    /// Creates an empty preview surface with stable layout dimensions.
    OfflineSkinPreviewPanel() {
        setName("offlineSkinPreview");
        setPreferredSize(new Dimension(320, 360));
        setMinimumSize(new Dimension(240, 300));
        @Nullable Color configuredBackground = UIManager.getColor("Panel.background");
        @Nullable Color configuredForeground = UIManager.getColor("Label.foreground");
        setBackground(configuredBackground == null ? Color.LIGHT_GRAY : configuredBackground);
        setForeground(configuredForeground == null ? Color.DARK_GRAY : configuredForeground);
        setBorder(BorderFactory.createEtchedBorder());
        setOpaque(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        installInteraction();
    }

    /// Displays one decoded skin preview and clears any previous message.
    ///
    /// @param preview decoded local or bundled skin images
    void showPreview(OfflineSkinPreview preview) {
        EdtDispatcher.requireEventDispatchThread();
        OfflineSkinPreview checked = Objects.requireNonNull(preview, "preview");
        skinImage = checked.skin();
        capeImage = checked.cape();
        textureModel = checked.model();
        message = " ";
        animationSeconds = 0.0;
        updateAnimationTimer();
        repaint();
    }

    /// Replaces the image with a localized loading, remote-source, or failure state.
    ///
    /// @param text localized message
    void showMessage(String text) {
        EdtDispatcher.requireEventDispatchThread();
        skinImage = null;
        capeImage = null;
        message = Objects.requireNonNull(text, "text");
        updateAnimationTimer();
        repaint();
    }

    /// Selects the movement cycle rendered by the preview.
    ///
    /// @param value movement cycle
    void setMotion(SkinPreviewMotion value) {
        EdtDispatcher.requireEventDispatchThread();
        motion = Objects.requireNonNull(value, "value");
        if (motion == SkinPreviewMotion.SPRINTING) {
            posture = SkinPreviewPosture.STANDING;
        }
        if (motion != SkinPreviewMotion.IDLE) {
            updateAnimationTimer();
        }
        repaint();
    }

    /// Selects the body posture rendered by the preview.
    ///
    /// @param value body posture
    void setPosture(SkinPreviewPosture value) {
        EdtDispatcher.requireEventDispatchThread();
        posture = Objects.requireNonNull(value, "value");
        if (posture != SkinPreviewPosture.STANDING && motion == SkinPreviewMotion.SPRINTING) {
            motion = SkinPreviewMotion.WALKING;
        }
        repaint();
    }

    /// Returns the accumulated rotation for focused interaction tests.
    ///
    /// @return current horizontal rotation in degrees
    double yawDegrees() {
        return yawDegrees;
    }

    /// Returns the accumulated vertical rotation.
    ///
    /// @return current vertical rotation in degrees
    double pitchDegrees() {
        return pitchDegrees;
    }

    /// Returns the current wheel zoom factor.
    ///
    /// @return zoom factor
    double zoomFactor() {
        return zoomFactor;
    }

    /// Returns the selected movement cycle.
    ///
    /// @return movement cycle
    SkinPreviewMotion motion() {
        return motion;
    }

    /// Returns the selected body posture.
    ///
    /// @return body posture
    SkinPreviewPosture posture() {
        return posture;
    }

    /// Returns an accessibility context for the custom-painted preview surface.
    ///
    /// @return stable accessible component context
    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleOfflineSkinPreviewPanel();
        }
        return accessibleContext;
    }

    /// Paints the current model or localized non-image state.
    ///
    /// @param graphics Swing paint destination
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D paint = (Graphics2D) graphics.create();
        try {
            paint.setColor(backgroundColor());
            paint.fillRect(0, 0, getWidth(), getHeight());
            @Nullable BufferedImage currentSkin = skinImage;
            if (currentSkin == null) {
                paintMessage(paint);
                return;
            }
            SoftwareSkinRenderer.render(
                    paint,
                    getWidth(),
                    getHeight(),
                    currentSkin,
                    capeImage,
                    textureModel,
                    motion,
                    posture,
                    yawDegrees,
                    pitchDegrees,
                    zoomFactor,
                    animationSeconds);
        } finally {
            paint.dispose();
        }
    }

    /// Starts animation only while a decodable preview is attached to a displayable hierarchy.
    @Override
    public void addNotify() {
        super.addNotify();
        updateAnimationTimer();
    }

    /// Stops the animation timer when the preview leaves its displayable hierarchy.
    @Override
    public void removeNotify() {
        animationTimer.stop();
        super.removeNotify();
    }

    /// Installs continuous rotation and wheel zoom handlers without resizing the panel.
    private void installInteraction() {
        MouseAdapter rotation = new MouseAdapter() {
            /// Captures the starting point and orientation of one drag gesture.
            ///
            /// @param event mouse press event
            @Override
            public void mousePressed(MouseEvent event) {
                dragOriginX = event.getX();
                dragOriginY = event.getY();
                dragOriginYaw = yawDegrees;
                dragOriginPitch = pitchDegrees;
            }

            /// Rotates the preview continuously from horizontal and vertical drag distance.
            ///
            /// @param event mouse drag event
            @Override
            public void mouseDragged(MouseEvent event) {
                @Nullable Integer originX = dragOriginX;
                @Nullable Integer originY = dragOriginY;
                if (originX == null || originY == null) {
                    return;
                }
                yawDegrees = dragOriginYaw + (event.getX() - originX) * 0.7;
                pitchDegrees = clamp(
                        dragOriginPitch - (event.getY() - originY) * 0.65,
                        MIN_PITCH,
                        MAX_PITCH);
                repaint();
            }

            /// Ends the current rotation gesture.
            ///
            /// @param event mouse release event
            @Override
            public void mouseReleased(MouseEvent event) {
                dragOriginX = null;
                dragOriginY = null;
            }
        };
        addMouseListener(rotation);
        addMouseMotionListener(rotation);
        addMouseWheelListener(new MouseWheelListener() {
            /// Applies wheel zoom around the current fitted scale.
            ///
            /// @param event mouse wheel event
            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                zoomFactor = clamp(
                        zoomFactor * Math.pow(1.1, -event.getPreciseWheelRotation()),
                        MIN_ZOOM,
                        MAX_ZOOM);
                repaint();
            }
        });
    }

    /// Starts or stops the animation timer from current preview state.
    private void updateAnimationTimer() {
        if (skinImage != null && isDisplayable()) {
            animationTimer.start();
        } else {
            animationTimer.stop();
        }
    }

    /// Paints the current localized non-image state in the center of the stable preview surface.
    ///
    /// @param paint prepared preview graphics
    private void paintMessage(Graphics2D paint) {
        paint.setColor(foregroundColor());
        FontMetrics metrics = paint.getFontMetrics();
        int availableWidth = Math.max(1, getWidth() - PREVIEW_PADDING * 2);
        String clipped = clipText(message, metrics, availableWidth);
        int x = Math.max(PREVIEW_PADDING, (getWidth() - metrics.stringWidth(clipped)) / 2);
        int y = Math.max(metrics.getAscent(), (getHeight() + metrics.getAscent()) / 2);
        paint.drawString(clipped, x, y);
    }

    /// Clips one localized message to the available preview width.
    ///
    /// @param text localized message
    /// @param metrics current font metrics
    /// @param availableWidth available pixel width
    /// @return original or ellipsis-clipped text
    private static String clipText(String text, FontMetrics metrics, int availableWidth) {
        if (metrics.stringWidth(text) <= availableWidth) {
            return text;
        }
        String ellipsis = "...";
        int limit = Math.max(0, text.length());
        while (limit > 0 && metrics.stringWidth(text.substring(0, limit) + ellipsis) > availableWidth) {
            --limit;
        }
        return text.substring(0, limit) + ellipsis;
    }

    /// Resolves the active Swing panel background with a stable fallback.
    ///
    /// @return preview background color
    private Color backgroundColor() {
        @Nullable Color configured = UIManager.getColor("Panel.background");
        @Nullable Color componentColor = getBackground();
        return configured != null
                ? configured
                : componentColor == null ? Color.LIGHT_GRAY : componentColor;
    }

    /// Resolves the active Swing label foreground with a stable fallback.
    ///
    /// @return preview text color
    private Color foregroundColor() {
        @Nullable Color configured = UIManager.getColor("Label.foreground");
        @Nullable Color componentColor = getForeground();
        return configured != null
                ? configured
                : componentColor == null ? Color.DARK_GRAY : componentColor;
    }

    /// Clamps one scalar value.
    ///
    /// @param value input value
    /// @param minimum lower bound
    /// @param maximum upper bound
    /// @return bounded value
    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /// Accessibility bridge for the custom-painted skin preview.
    @NotNullByDefault
    protected final class AccessibleOfflineSkinPreviewPanel extends AccessibleJComponent {
    }
}
