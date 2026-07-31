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
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises the offscreen Swing skin projection and its mouse rotation interaction.
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

    /// Horizontal drag input changes preview yaw without changing component dimensions.
    @Test
    public void rotatesPreviewWithMouseDrag() {
        AtomicReference<Double> yaw = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            OfflineSkinPreviewPanel panel = new OfflineSkinPreviewPanel();
            panel.setSize(320, 360);
            panel.dispatchEvent(mouseEvent(panel, MouseEvent.MOUSE_PRESSED, 80));
            panel.dispatchEvent(mouseEvent(panel, MouseEvent.MOUSE_DRAGGED, 180));
            panel.dispatchEvent(mouseEvent(panel, MouseEvent.MOUSE_RELEASED, 180));
            yaw.set(panel.yawDegrees());
            assertTrue(panel.getWidth() == 320 && panel.getHeight() == 360);
        });

        assertTrue(yaw.get() > 45.0);
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

    /// Creates one synthetic mouse event at a horizontal coordinate.
    ///
    /// @param panel event target
    /// @param identifier AWT mouse event identifier
    /// @param x horizontal coordinate
    /// @return synthetic event
    private static MouseEvent mouseEvent(OfflineSkinPreviewPanel panel, int identifier, int x) {
        return new MouseEvent(
                panel,
                identifier,
                System.currentTimeMillis(),
                0,
                x,
                120,
                1,
                false,
                MouseEvent.BUTTON1);
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
}
