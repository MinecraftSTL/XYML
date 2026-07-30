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

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLightLaf;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that collapsed shell selectors reveal the wallpaper without losing interaction feedback.
@NotNullByDefault
public final class ShellDropdownButtonTest {
    /// Distinct background pixel used for exact off-screen paint comparison.
    private static final int BACKDROP_ARGB = new Color(0x31, 0xA9, 0xE1).getRGB();

    /// The collapsed state stays transparent while FlatLaf still paints its rollover surface.
    @Test
    public void keepsRestingSurfaceTransparentAndRolloverVisible() {
        EdtDispatcher.executeAndWait(() -> {
            assertTrue(FlatLightLaf.setup());
            ShellDropdownButton button = new ShellDropdownButton();
            button.setText("Account");
            button.setSize(220, 36);

            int restingPixel = centerPixel(button);
            button.getModel().setRollover(true);
            int rolloverPixel = centerPixel(button);

            assertAll(
                    () -> assertFalse(button.isOpaque()),
                    () -> assertTrue(button.isContentAreaFilled()),
                    () -> assertEquals(
                            FlatClientProperties.BUTTON_TYPE_BORDERLESS,
                            button.getClientProperty(FlatClientProperties.BUTTON_TYPE)),
                    () -> assertEquals(BACKDROP_ARGB, restingPixel),
                    () -> assertNotEquals(BACKDROP_ARGB, rolloverPixel));
        });
    }

    /// Paints the center of one selector over a known opaque background.
    ///
    /// @param button configured selector button
    /// @return center ARGB pixel after painting
    private static int centerPixel(ShellDropdownButton button) {
        BufferedImage image = new BufferedImage(
                button.getWidth(),
                button.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(BACKDROP_ARGB, true));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            button.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
    }
}
