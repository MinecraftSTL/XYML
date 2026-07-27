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
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Objects;

/// Pixel-accurate, network-free Swing preview for a decoded Minecraft skin and optional cape.
///
/// Horizontal mouse dragging rotates the visible texture face in quarter-turn steps. Rendering keeps nearest-neighbor
/// sampling so bundled and user-provided pixel art remains crisp at every supported panel size.
@NotNullByDefault
final class OfflineSkinPreviewPanel extends JComponent {
    /// Logical player width in texture pixels for the front projection.
    private static final int PLAYER_WIDTH = 16;

    /// Logical player height in texture pixels for the front projection.
    private static final int PLAYER_HEIGHT = 32;

    /// Empty border retained around the projected player.
    private static final int PREVIEW_PADDING = 24;

    /// Decoded player texture currently rendered, or null for a textual state.
    private @Nullable BufferedImage skinImage;

    /// Decoded cape texture currently rendered behind the player, or null when absent.
    private @Nullable BufferedImage capeImage;

    /// Arm width represented by the current texture.
    private TextureModel textureModel = TextureModel.WIDE;

    /// Localized message rendered while no decoded preview is available.
    private String message = " ";

    /// Accumulated horizontal preview rotation in degrees.
    private double yawDegrees;

    /// Last drag coordinate, or null while no mouse drag is active.
    private @Nullable Integer dragOriginX;

    /// Rotation captured at the start of the current drag gesture.
    private double dragOriginYaw;

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
        installRotationInteraction();
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
        repaint();
    }

    /// Returns the accumulated rotation for focused interaction tests.
    ///
    /// @return current horizontal rotation in degrees
    double yawDegrees() {
        return yawDegrees;
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

    /// Paints either the stable textual state or the decoded player projection.
    ///
    /// @param graphics Swing paint destination
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D paint = (Graphics2D) graphics.create();
        try {
            paint.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            paint.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            paint.setColor(backgroundColor());
            paint.fillRect(0, 0, getWidth(), getHeight());

            @Nullable BufferedImage currentSkin = skinImage;
            if (currentSkin == null) {
                paintMessage(paint);
                return;
            }
            paintPlayer(paint, currentSkin, capeImage);
        } finally {
            paint.dispose();
        }
    }

    /// Installs drag handlers that update the horizontal preview orientation without resizing the panel.
    private void installRotationInteraction() {
        MouseAdapter rotation = new MouseAdapter() {
            /// Captures the starting point of one rotation gesture.
            ///
            /// @param event mouse press event
            @Override
            public void mousePressed(MouseEvent event) {
                dragOriginX = event.getX();
                dragOriginYaw = yawDegrees;
            }

            /// Rotates the preview in direct proportion to horizontal drag distance.
            ///
            /// @param event mouse drag event
            @Override
            public void mouseDragged(MouseEvent event) {
                @Nullable Integer origin = dragOriginX;
                if (origin == null) {
                    return;
                }
                yawDegrees = dragOriginYaw + (event.getX() - origin) * 0.9;
                repaint();
            }

            /// Ends the current rotation gesture.
            ///
            /// @param event mouse release event
            @Override
            public void mouseReleased(MouseEvent event) {
                dragOriginX = null;
            }
        };
        addMouseListener(rotation);
        addMouseMotionListener(rotation);
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

    /// Paints the cape, body shadow, and six player parts for the current quarter-turn orientation.
    ///
    /// @param paint prepared preview graphics
    /// @param skin decoded skin texture
    /// @param cape decoded cape texture, or null
    private void paintPlayer(
            Graphics2D paint,
            BufferedImage skin,
            @Nullable BufferedImage cape) {
        int drawableWidth = Math.max(1, getWidth() - PREVIEW_PADDING * 2);
        int drawableHeight = Math.max(1, getHeight() - PREVIEW_PADDING * 2);
        int scale = Math.max(1, Math.min(drawableWidth / PLAYER_WIDTH, drawableHeight / PLAYER_HEIGHT));
        int playerWidth = PLAYER_WIDTH * scale;
        int playerHeight = PLAYER_HEIGHT * scale;
        int originX = (getWidth() - playerWidth) / 2;
        int originY = (getHeight() - playerHeight) / 2;
        int facing = facingIndex();

        paint.setColor(new Color(0, 0, 0, 38));
        paint.fillOval(
                originX + 2 * scale,
                originY + playerHeight - scale,
                playerWidth - 4 * scale,
                Math.max(2, scale * 2));
        if (cape != null) {
            paintCape(paint, cape, originX, originY, scale, facing);
        }

        int armWidth = textureModel == TextureModel.SLIM ? 3 : 4;
        int bodyX = originX + 4 * scale;
        int bodyY = originY + 8 * scale;
        paintPart(paint, skin, bodyX, originY, 8 * scale, 8 * scale, headRegion(facing));
        paintPart(paint, skin, bodyX, bodyY, 8 * scale, 12 * scale, bodyRegion(facing));
        paintPart(
                paint,
                skin,
                bodyX - armWidth * scale,
                bodyY,
                armWidth * scale,
                12 * scale,
                rightArmRegion(facing));
        paintPart(
                paint,
                skin,
                bodyX + 8 * scale,
                bodyY,
                armWidth * scale,
                12 * scale,
                leftArmRegion(skin, facing));
        paintPart(
                paint,
                skin,
                bodyX,
                bodyY + 12 * scale,
                4 * scale,
                12 * scale,
                rightLegRegion(facing));
        paintPart(
                paint,
                skin,
                bodyX + 4 * scale,
                bodyY + 12 * scale,
                4 * scale,
                12 * scale,
                leftLegRegion(skin, facing));
    }

    /// Paints a cape behind the player, widening it when the back is visible.
    ///
    /// @param paint prepared preview graphics
    /// @param cape decoded cape texture
    /// @param originX player projection origin X
    /// @param originY player projection origin Y
    /// @param scale logical-pixel scale
    /// @param facing current quarter-turn orientation
    private static void paintCape(
            Graphics2D paint,
            BufferedImage cape,
            int originX,
            int originY,
            int scale,
            int facing) {
        int width = facing == 2 ? 10 * scale : Math.max(scale, 2 * scale);
        int x = facing == 2
                ? originX + 3 * scale
                : originX + (facing == 1 ? 2 : 12) * scale;
        drawRegion(paint, cape, x, originY + 8 * scale, width, 16 * scale, 1, 1, 10, 16);
    }

    /// Paints one player part with crisp source-coordinate scaling.
    ///
    /// @param paint prepared preview graphics
    /// @param image decoded skin texture
    /// @param x destination X
    /// @param y destination Y
    /// @param width destination width
    /// @param height destination height
    /// @param region source region
    private static void paintPart(
            Graphics2D paint,
            BufferedImage image,
            int x,
            int y,
            int width,
            int height,
            TextureRegion region) {
        drawRegion(paint, image, x, y, width, height, region.x(), region.y(), region.width(), region.height());
    }

    /// Draws one canonical 64-pixel texture region from regular or high-resolution skin images.
    ///
    /// @param paint prepared preview graphics
    /// @param image decoded texture
    /// @param destinationX destination X
    /// @param destinationY destination Y
    /// @param destinationWidth destination width
    /// @param destinationHeight destination height
    /// @param sourceX canonical source X
    /// @param sourceY canonical source Y
    /// @param sourceWidth canonical source width
    /// @param sourceHeight canonical source height
    private static void drawRegion(
            Graphics2D paint,
            BufferedImage image,
            int destinationX,
            int destinationY,
            int destinationWidth,
            int destinationHeight,
            int sourceX,
            int sourceY,
            int sourceWidth,
            int sourceHeight) {
        double horizontalScale = image.getWidth() / 64.0;
        double verticalScale = image.getHeight() / (image.getHeight() >= image.getWidth() ? 64.0 : 32.0);
        int x1 = (int) Math.round(sourceX * horizontalScale);
        int y1 = (int) Math.round(sourceY * verticalScale);
        int x2 = Math.min(image.getWidth(), (int) Math.round((sourceX + sourceWidth) * horizontalScale));
        int y2 = Math.min(image.getHeight(), (int) Math.round((sourceY + sourceHeight) * verticalScale));
        if (x1 >= x2 || y1 >= y2) {
            return;
        }
        paint.drawImage(
                image,
                destinationX,
                destinationY,
                destinationX + destinationWidth,
                destinationY + destinationHeight,
                x1,
                y1,
                x2,
                y2,
                null);
    }

    /// Resolves the visible face from the continuous drag rotation.
    ///
    /// @return front, left, back, or right index
    private int facingIndex() {
        return Math.floorMod((int) Math.floor((yawDegrees + 45.0) / 90.0), 4);
    }

    /// Returns the visible base head region.
    ///
    /// @param facing quarter-turn orientation
    /// @return canonical source region
    private static TextureRegion headRegion(int facing) {
        return faceRegion(facing, 8, 0, 16, 24, 8, 8);
    }

    /// Returns the visible base torso region.
    ///
    /// @param facing quarter-turn orientation
    /// @return canonical source region
    private static TextureRegion bodyRegion(int facing) {
        return faceRegion(facing, 20, 16, 28, 32, 20, 8, 12);
    }

    /// Returns the visible base right-arm region.
    ///
    /// @param facing quarter-turn orientation
    /// @return canonical source region
    private static TextureRegion rightArmRegion(int facing) {
        return faceRegion(facing, 44, 40, 48, 52, 20, 4, 12);
    }

    /// Returns the visible base left-arm region, falling back to the legacy right arm.
    ///
    /// @param image decoded skin texture
    /// @param facing quarter-turn orientation
    /// @return canonical source region
    private static TextureRegion leftArmRegion(BufferedImage image, int facing) {
        return isModern(image)
                ? faceRegion(facing, 36, 32, 40, 44, 52, 4, 12)
                : rightArmRegion(facing);
    }

    /// Returns the visible base right-leg region.
    ///
    /// @param facing quarter-turn orientation
    /// @return canonical source region
    private static TextureRegion rightLegRegion(int facing) {
        return faceRegion(facing, 4, 0, 8, 12, 20, 4, 12);
    }

    /// Returns the visible base left-leg region, falling back to the legacy right leg.
    ///
    /// @param image decoded skin texture
    /// @param facing quarter-turn orientation
    /// @return canonical source region
    private static TextureRegion leftLegRegion(BufferedImage image, int facing) {
        return isModern(image)
                ? faceRegion(facing, 20, 16, 24, 28, 52, 4, 12)
                : rightLegRegion(facing);
    }

    /// Creates one equal-size face selection where every face uses the same source Y coordinate.
    ///
    /// @param facing quarter-turn orientation
    /// @param frontX front source X
    /// @param rightX right source X
    /// @param leftX left source X
    /// @param backX back source X
    /// @param sourceY source Y
    /// @param size square face size
    /// @return selected canonical region
    private static TextureRegion faceRegion(
            int facing,
            int frontX,
            int rightX,
            int leftX,
            int backX,
            int sourceY,
            int size) {
        return faceRegion(facing, frontX, rightX, leftX, backX, sourceY, size, size);
    }

    /// Creates one rectangular face selection where every face uses the same source Y coordinate.
    ///
    /// @param facing quarter-turn orientation
    /// @param frontX front source X
    /// @param rightX right source X
    /// @param leftX left source X
    /// @param backX back source X
    /// @param sourceY source Y
    /// @param width source width
    /// @param height source height
    /// @return selected canonical region
    private static TextureRegion faceRegion(
            int facing,
            int frontX,
            int rightX,
            int leftX,
            int backX,
            int sourceY,
            int width,
            int height) {
        int sourceX = switch (facing) {
            case 1 -> leftX;
            case 2 -> backX;
            case 3 -> rightX;
            default -> frontX;
        };
        return new TextureRegion(sourceX, sourceY, width, height);
    }

    /// Reports whether a skin image contains the post-1.8 lower texture half.
    ///
    /// @param image decoded skin texture
    /// @return whether independent left limbs are available
    private static boolean isModern(BufferedImage image) {
        return image.getHeight() >= image.getWidth();
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

    /// Canonical subregion in a 64-pixel Minecraft texture layout.
    ///
    /// @param x source X
    /// @param y source Y
    /// @param width source width
    /// @param height source height
    @NotNullByDefault
    private record TextureRegion(int x, int y, int width, int height) {
    }

    /// Accessibility bridge for the custom-painted skin preview.
    @NotNullByDefault
    protected final class AccessibleOfflineSkinPreviewPanel extends AccessibleJComponent {
    }
}
