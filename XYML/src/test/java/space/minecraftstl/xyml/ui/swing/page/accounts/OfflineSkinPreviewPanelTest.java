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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.auth.yggdrasil.TextureModel;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises the offscreen Swing skin projection and its mouse interactions.
@NotNullByDefault
public final class OfflineSkinPreviewPanelTest {
    /// A decoded texture paints visible player pixels into a stable offscreen surface.
    @Test
    public void paintsDecodedSkinOffscreen() {
        BufferedImage texture = solidTexture(new Color(217, 48, 92, 255));
        AtomicReference<BufferedImage> painted = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            OfflineSkinPreviewPanel panel = new OfflineSkinPreviewPanel();
            panel.setSize(320, 360);
            panel.showPreview(new OfflineSkinPreview(TextureModel.WIDE, texture, null));
            BufferedImage output = new BufferedImage(320, 360, BufferedImage.TYPE_INT_ARGB);
            panel.paint(output.getGraphics());
            painted.set(output);
        });

        assertTrue(countColor(painted.get(), texture.getRGB(0, 0)) > 1_000);
    }

    /// An opaque modern outer layer covers the base cuboids despite the default rotated view.
    @Test
    public void paintsOpaqueOuterLayer() {
        BufferedImage texture = doubleLayerTexture();
        AtomicReference<BufferedImage> painted = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            OfflineSkinPreviewPanel panel = new OfflineSkinPreviewPanel();
            panel.setSize(320, 360);
            panel.showPreview(new OfflineSkinPreview(TextureModel.WIDE, texture, null));
            BufferedImage output = new BufferedImage(320, 360, BufferedImage.TYPE_INT_ARGB);
            panel.paint(output.getGraphics());
            painted.set(output);
        });

        assertAll(
                () -> assertTrue(countColor(painted.get(), new Color(220, 40, 40).getRGB()) > 1_000),
                () -> assertEquals(0, countColor(painted.get(), new Color(40, 80, 220).getRGB())));
    }

    /// Verifies crouch walking keeps both rendered legs connected to the torso.
    @Test
    public void keepsCrouchLegsAttachedWhileWalking() {
        BufferedImage texture = solidTexture(new Color(217, 48, 92, 255));
        for (double seconds : new double[] {0.0, 0.45, 0.9, 1.35}) {
            BufferedImage output = renderFrame(
                    texture,
                    SkinPreviewMotion.WALKING,
                    SkinPreviewPosture.SNEAKING,
                    seconds,
                    22.5,
                    10.0);
            assertEquals(1, countOpaqueComponents(output), "seconds=" + seconds);
        }
    }

    /// Verifies airborne postures omit the grounded shadow while standing keeps it.
    @Test
    public void omitsGroundShadowForAirbornePostures() {
        BufferedImage texture = solidTexture(new Color(217, 48, 92, 255));
        BufferedImage standing = renderFrame(texture, SkinPreviewMotion.IDLE, 0.0);
        BufferedImage riding = renderFrame(
                texture,
                SkinPreviewMotion.IDLE,
                SkinPreviewPosture.RIDING,
                0.0,
                22.5,
                10.0);
        BufferedImage swimming = renderFrame(
                texture,
                SkinPreviewMotion.IDLE,
                SkinPreviewPosture.SWIMMING,
                0.0,
                22.5,
                10.0);
        assertAll(
                () -> assertTrue(countTranslucentPixels(standing) > 0),
                () -> assertEquals(0, countTranslucentPixels(riding)),
                () -> assertEquals(0, countTranslucentPixels(swimming)));
    }

    /// Verifies the swimming walk advances the hand stroke between distinct phases.
    @Test
    public void animatesSwimmingWalkHands() {
        BufferedImage texture = solidTexture(new Color(217, 48, 92, 255));
        BufferedImage first = renderFrame(
                texture,
                SkinPreviewMotion.WALKING,
                SkinPreviewPosture.SWIMMING,
                0.0,
                22.5,
                10.0);
        BufferedImage second = renderFrame(
                texture,
                SkinPreviewMotion.WALKING,
                SkinPreviewPosture.SWIMMING,
                0.45,
                22.5,
                10.0);
        assertTrue(fingerprint(first) != fingerprint(second));
    }

    /// Fixed projection keeps the torso and head anchored during the sprint animation.
    @Test
    public void keepsSprintTorsoFixed() {
        BufferedImage texture = solidTexture(new Color(217, 48, 92, 255));
        BufferedImage first = renderFrame(texture, SkinPreviewMotion.SPRINTING, 0.0);
        BufferedImage second = renderFrame(texture, SkinPreviewMotion.SPRINTING, 0.45);

        int firstTop = topOpaqueY(first);
        int secondTop = topOpaqueY(second);
        assertAll(
                () -> assertTrue(firstTop > 0),
                () -> assertTrue(Math.abs(firstTop - secondTop) <= 1));
    }

    /// Fully transparent textures do not gain gray shading pixels.
    @Test
    public void keepsTransparentTextureTransparent() {
        BufferedImage texture = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        BufferedImage output = renderFrame(texture, SkinPreviewMotion.IDLE, 0.0);

        for (int y = 0; y < output.getHeight() / 2; ++y) {
            for (int x = 0; x < output.getWidth(); ++x) {
                assertEquals(0, output.getRGB(x, y) >>> 24);
            }
        }
    }

    /// Horizontal drag input changes preview yaw without changing component dimensions.
    @Test
    public void rotatesPreviewWithMouseDrag() {
        AtomicReference<Double> yaw = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            OfflineSkinPreviewPanel panel = new OfflineSkinPreviewPanel();
            panel.setSize(320, 360);
            panel.dispatchEvent(mouseEvent(panel, MouseEvent.MOUSE_PRESSED, 80, 120));
            panel.dispatchEvent(mouseEvent(panel, MouseEvent.MOUSE_DRAGGED, 180, 120));
            panel.dispatchEvent(mouseEvent(panel, MouseEvent.MOUSE_RELEASED, 180, 120));
            yaw.set(panel.yawDegrees());
            assertTrue(panel.getWidth() == 320 && panel.getHeight() == 360);
        });

        assertTrue(yaw.get() > 45.0);
    }

    /// Vertical drag input changes preview pitch without invoking the wheel path.
    @Test
    public void tiltsPreviewWithVerticalMouseDrag() {
        AtomicReference<Double> pitch = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            OfflineSkinPreviewPanel panel = new OfflineSkinPreviewPanel();
            panel.setSize(320, 360);
            panel.dispatchEvent(mouseEvent(panel, MouseEvent.MOUSE_PRESSED, 120, 80));
            panel.dispatchEvent(mouseEvent(panel, MouseEvent.MOUSE_DRAGGED, 120, 20));
            panel.dispatchEvent(mouseEvent(panel, MouseEvent.MOUSE_RELEASED, 120, 20));
            pitch.set(panel.pitchDegrees());
        });

        assertTrue(pitch.get() < -20.0);
    }

    /// Mouse-wheel input changes and bounds the fitted zoom factor.
    @Test
    public void zoomsPreviewWithMouseWheel() {
        AtomicReference<Double> zoom = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            OfflineSkinPreviewPanel panel = new OfflineSkinPreviewPanel();
            panel.setSize(320, 360);
            panel.dispatchEvent(wheelEvent(panel, -3.0));
            zoom.set(panel.zoomFactor());
        });

        assertTrue(zoom.get() > 1.2);
    }

    /// Movement and posture selections produce distinct offscreen frames.
    @Test
    public void rendersDistinctStateFrames() {
        AtomicReference<List<Integer>> fingerprints = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            OfflineSkinPreviewPanel panel = new OfflineSkinPreviewPanel();
            panel.setSize(320, 360);
            panel.showPreview(new OfflineSkinPreview(
                    TextureModel.WIDE,
                    solidTexture(new Color(217, 48, 92, 255)),
                    null));
            List<State> states = List.of(
                    new State(SkinPreviewMotion.IDLE, SkinPreviewPosture.STANDING),
                    new State(SkinPreviewMotion.WALKING, SkinPreviewPosture.STANDING),
                    new State(SkinPreviewMotion.IDLE, SkinPreviewPosture.SNEAKING),
                    new State(SkinPreviewMotion.IDLE, SkinPreviewPosture.RIDING),
                    new State(SkinPreviewMotion.WALKING, SkinPreviewPosture.SWIMMING));
            List<Integer> rendered = new ArrayList<>();
            for (State state : states) {
                panel.setMotion(state.motion());
                panel.setPosture(state.posture());
                BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_ARGB);
                panel.paint(image.getGraphics());
                rendered.add(fingerprint(image));
            }
            fingerprints.set(List.copyOf(rendered));
        });

        assertEquals(fingerprints.get().size(), new HashSet<>(fingerprints.get()).size());
    }

    /// Movement and posture state remain valid while independently selectable.
    @Test
    public void storesMovementAndPosture() {
        EdtDispatcher.executeAndWait(() -> {
            OfflineSkinPreviewPanel panel = new OfflineSkinPreviewPanel();
            panel.setPosture(SkinPreviewPosture.SWIMMING);
            panel.setMotion(SkinPreviewMotion.SPRINTING);
            assertAll(
                    () -> assertEquals(SkinPreviewMotion.SPRINTING, panel.motion()),
                    () -> assertEquals(SkinPreviewPosture.STANDING, panel.posture()));
            panel.setPosture(SkinPreviewPosture.SWIMMING);
            assertAll(
                    () -> assertEquals(SkinPreviewMotion.WALKING, panel.motion()),
                    () -> assertEquals(SkinPreviewPosture.SWIMMING, panel.posture()));
        });
    }

    /// Verifies the vanilla model axes map signed head faces to the expected camera directions.
    @Test
    public void keepsSignedHeadFacesOnVanillaAxes() {
        BufferedImage skin = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = skin.createGraphics();
        try {
            graphics.setColor(new Color(220, 40, 40));
            graphics.fillRect(8, 8, 8, 8);
            graphics.setColor(new Color(64, 128, 255));
            graphics.fillRect(24, 8, 8, 8);
            graphics.setColor(new Color(40, 180, 80));
            graphics.fillRect(0, 8, 8, 8);
            graphics.setColor(new Color(220, 180, 40));
            graphics.fillRect(16, 8, 8, 8);
        } finally {
            graphics.dispose();
        }
        BufferedImage front = renderFrame(skin, SkinPreviewMotion.IDLE, SkinPreviewPosture.STANDING, 0.0, 0.0, 0.0);
        BufferedImage back = renderFrame(skin, SkinPreviewMotion.IDLE, SkinPreviewPosture.STANDING, 0.0, 180.0, 0.0);
        BufferedImage right = renderFrame(skin, SkinPreviewMotion.IDLE, SkinPreviewPosture.STANDING, 0.0, 90.0, 0.0);
        BufferedImage left = renderFrame(skin, SkinPreviewMotion.IDLE, SkinPreviewPosture.STANDING, 0.0, -90.0, 0.0);

        assertAll(
                () -> assertTrue(countColor(front, new Color(220, 40, 40).getRGB()) > 0),
                () -> assertEquals(0, countColor(front, new Color(50, 100, 199).getRGB())),
                () -> assertTrue(countColor(back, new Color(50, 100, 199).getRGB()) > 0),
                () -> assertEquals(0, countColor(back, new Color(220, 40, 40).getRGB())),
                () -> assertTrue(countColor(right, new Color(35, 158, 70).getRGB()) > 0),
                () -> assertTrue(countColor(left, new Color(194, 158, 35).getRGB()) > 0));
    }

    /// Renders one deterministic software frame with the default posture.
    ///
    /// @param texture decoded skin texture
    /// @param motion movement cycle
    /// @param seconds animation time
    /// @return rendered frame
    private static BufferedImage renderFrame(BufferedImage texture, SkinPreviewMotion motion, double seconds) {
        return renderFrame(
                texture,
                motion,
                SkinPreviewPosture.STANDING,
                seconds,
                30.0,
                15.0);
    }

    /// Renders one deterministic software frame with an explicit posture and camera.
    ///
    /// @param texture decoded skin texture
    /// @param motion movement cycle
    /// @param posture body posture
    /// @param seconds animation time
    /// @param yaw camera yaw in degrees
    /// @param pitch camera pitch in degrees
    /// @return rendered frame
    private static BufferedImage renderFrame(
            BufferedImage texture,
            SkinPreviewMotion motion,
            SkinPreviewPosture posture,
            double seconds,
            double yaw,
            double pitch) {
        BufferedImage output = new BufferedImage(320, 360, BufferedImage.TYPE_INT_ARGB);
        SoftwareSkinRenderer.render(
                output.createGraphics(),
                output.getWidth(),
                output.getHeight(),
                texture,
                null,
                TextureModel.WIDE,
                motion,
                posture,
                yaw,
                pitch,
                1.0,
                seconds);
        return output;
    }

    /// Finds the first visible Y coordinate.
    ///
    /// @param image rendered frame
    /// @return topmost opaque Y, or -1 when no pixels are visible
    private static int topOpaqueY(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); ++y) {
            for (int x = 0; x < image.getWidth(); ++x) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    return y;
                }
            }
        }
        return -1;
    }

    /// Counts pixels that are partially transparent.
    ///
    /// @param image rendered frame
    /// @return number of pixels with alpha between 1 and 254
    private static int countTranslucentPixels(BufferedImage image) {
        int matches = 0;
        for (int y = 0; y < image.getHeight(); ++y) {
            for (int x = 0; x < image.getWidth(); ++x) {
                int alpha = image.getRGB(x, y) >>> 24;
                if (alpha > 0 && alpha < 255) {
                    ++matches;
                }
            }
        }
        return matches;
    }

    /// Counts opaque, four-connected silhouette components while ignoring the soft shadow.
    ///
    /// @param image rendered frame
    /// @return number of connected components with alpha greater than 200
    private static int countOpaqueComponents(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        boolean[] visited = new boolean[width * height];
        int components = 0;
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int index = y * width + x;
                if (visited[index] || (image.getRGB(x, y) >>> 24) <= 200) {
                    continue;
                }
                ++components;
                Deque<Point> queue = new ArrayDeque<>();
                visited[index] = true;
                queue.add(new Point(x, y));
                while (!queue.isEmpty()) {
                    Point point = queue.removeFirst();
                    enqueueOpaqueNeighbor(queue, visited, image, point.x - 1, point.y);
                    enqueueOpaqueNeighbor(queue, visited, image, point.x + 1, point.y);
                    enqueueOpaqueNeighbor(queue, visited, image, point.x, point.y - 1);
                    enqueueOpaqueNeighbor(queue, visited, image, point.x, point.y + 1);
                }
            }
        }
        return components;
    }

    /// Enqueues one opaque four-connected neighbor when it is inside the image.
    ///
    /// @param queue pending pixels
    /// @param visited visited-pixel flags
    /// @param image rendered frame
    /// @param x neighbor X
    /// @param y neighbor Y
    private static void enqueueOpaqueNeighbor(
            Deque<Point> queue,
            boolean[] visited,
            BufferedImage image,
            int x,
            int y) {
        if (x < 0 || x >= image.getWidth() || y < 0 || y >= image.getHeight()) {
            return;
        }
        int index = y * image.getWidth() + x;
        if (visited[index] || (image.getRGB(x, y) >>> 24) <= 200) {
            return;
        }
        visited[index] = true;
        queue.addLast(new Point(x, y));
    }

    /// Creates a blue base skin with an opaque red modern outer layer.
    ///
    /// @return 64 by 64 double-layer texture
    private static BufferedImage doubleLayerTexture() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(image, 0, 0, 64, 64, new Color(220, 40, 40));
        fill(image, 0, 0, 32, 16, new Color(40, 80, 220));
        fill(image, 16, 16, 40, 32, new Color(40, 80, 220));
        fill(image, 40, 16, 56, 32, new Color(40, 80, 220));
        fill(image, 0, 16, 16, 32, new Color(40, 80, 220));
        fill(image, 32, 48, 48, 64, new Color(40, 80, 220));
        fill(image, 16, 48, 32, 64, new Color(40, 80, 220));
        return image;
    }

    /// Fills one half-open texture rectangle.
    ///
    /// @param image destination image
    /// @param x1 left X
    /// @param y1 top Y
    /// @param x2 right X
    /// @param y2 bottom Y
    /// @param color fill color
    private static void fill(BufferedImage image, int x1, int y1, int x2, int y2, Color color) {
        for (int y = y1; y < y2; ++y) {
            for (int x = x1; x < x2; ++x) {
                image.setRGB(x, y, color.getRGB());
            }
        }
    }

    /// Creates a uniformly opaque test skin.
    ///
    /// @param color fixture color
    /// @return 64 by 64 texture
    private static BufferedImage solidTexture(Color color) {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); ++y) {
            for (int x = 0; x < image.getWidth(); ++x) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        return image;
    }

    /// Creates one synthetic mouse event at a coordinate.
    ///
    /// @param panel event target
    /// @param identifier AWT mouse event identifier
    /// @param x horizontal coordinate
    /// @param y vertical coordinate
    /// @return synthetic event
    private static MouseEvent mouseEvent(OfflineSkinPreviewPanel panel, int identifier, int x, int y) {
        return new MouseEvent(
                panel,
                identifier,
                System.currentTimeMillis(),
                identifier == MouseEvent.MOUSE_DRAGGED ? MouseEvent.BUTTON1_DOWN_MASK : 0,
                x,
                y,
                1,
                false,
                MouseEvent.BUTTON1);
    }

    /// Creates one synthetic wheel event.
    ///
    /// @param panel event target
    /// @param rotation precise wheel rotation
    /// @return synthetic wheel event
    private static MouseWheelEvent wheelEvent(OfflineSkinPreviewPanel panel, double rotation) {
        return new MouseWheelEvent(
                panel,
                MouseWheelEvent.MOUSE_WHEEL,
                System.currentTimeMillis(),
                0,
                160,
                180,
                160,
                180,
                MouseEvent.NOBUTTON,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                1,
                (int) rotation);
    }

    /// Computes a sampled frame fingerprint.
    ///
    /// @param image rendered image
    /// @return sampled frame hash
    private static int fingerprint(BufferedImage image) {
        int hash = 1;
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                hash = 31 * hash + image.getRGB(x, y);
            }
        }
        return hash;
    }

    /// Counts pixels matching one exact ARGB value.
    ///
    /// @param image rendered image
    /// @param argb expected pixel
    /// @return number of matching pixels
    private static int countColor(BufferedImage image, int argb) {
        int matches = 0;
        for (int y = 0; y < image.getHeight(); ++y) {
            for (int x = 0; x < image.getWidth(); ++x) {
                if (image.getRGB(x, y) == argb) {
                    ++matches;
                }
            }
        }
        return matches;
    }

    /// One movement and posture fixture.
    ///
    /// @param motion movement cycle
    /// @param posture body posture
    @NotNullByDefault
    private record State(SkinPreviewMotion motion, SkinPreviewPosture posture) {
    }
}
