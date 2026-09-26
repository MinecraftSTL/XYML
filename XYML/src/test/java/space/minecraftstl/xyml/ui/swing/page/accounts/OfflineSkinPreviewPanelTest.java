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
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
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

        assertTrue(pitch.get() > 20.0);
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

    /// Renders one deterministic software frame.
    ///
    /// @param texture decoded skin texture
    /// @param motion movement cycle
    /// @param seconds animation time
    /// @return rendered frame
    private static BufferedImage renderFrame(BufferedImage texture, SkinPreviewMotion motion, double seconds) {
        BufferedImage output = new BufferedImage(320, 360, BufferedImage.TYPE_INT_ARGB);
        SoftwareSkinRenderer.render(
                output.createGraphics(),
                output.getWidth(),
                output.getHeight(),
                texture,
                null,
                TextureModel.WIDE,
                motion,
                SkinPreviewPosture.STANDING,
                30.0,
                15.0,
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
