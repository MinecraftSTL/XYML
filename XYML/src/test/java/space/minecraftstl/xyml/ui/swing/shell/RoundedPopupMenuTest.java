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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.SwingDesignTokens;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Shape;
import java.awt.Window;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Verifies exact-radius shell popup rendering without creating a native window.
@NotNullByDefault
public final class RoundedPopupMenuTest {
    /// Popup painting clips opaque child content to the configured outer radius and responds to zero radius.
    @Test
    public void clipsCompletePopupContentToCurrentRadius() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(FlatLightLaf.setup());
            RoundedPopupMenu popup = new RoundedPopupMenu();
            popup.setLayout(new BorderLayout());
            JPanel content = new JPanel();
            content.setBackground(new Color(37, 128, 211));
            popup.add(content, BorderLayout.CENTER);
            popup.setSize(new Dimension(240, 150));
            popup.doLayout();

            new SwingDesignTokens(12).applyTo(UIManager.getDefaults());
            BufferedImage rounded = render(popup);
            assertEquals(0, rounded.getRGB(0, 0) >>> 24);
            assertEquals(255, rounded.getRGB(rounded.getWidth() / 2, 0) >>> 24);
            assertEquals(0, popup.getClientProperty(FlatClientProperties.POPUP_BORDER_CORNER_RADIUS));
            assertEquals(Boolean.FALSE, popup.getClientProperty(FlatClientProperties.POPUP_DROP_SHADOW_PAINTED));
            assertEquals(Boolean.TRUE, popup.getClientProperty(FlatClientProperties.POPUP_FORCE_HEAVY_WEIGHT));

            new SwingDesignTokens(0).applyTo(UIManager.getDefaults());
            BufferedImage square = render(popup);
            assertEquals(255, square.getRGB(0, 0) >>> 24);
        });
    }

    /// A real heavyweight popup uses the exact Swing shape and clears it before the native window is cached.
    @Test
    public void shapesHeavyweightPopupWindowWhenSupported() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        assumeTrue(device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT));

        SwingUtilities.invokeAndWait(() -> {
            assertTrue(FlatLightLaf.setup());
            new SwingDesignTokens(12).applyTo(UIManager.getDefaults());
            JFrame frame = new JFrame("rounded-popup-test");
            RoundedPopupMenu popup = new RoundedPopupMenu();
            JButton invoker = new JButton("open");
            try {
                frame.add(invoker);
                frame.setSize(320, 180);
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
                popup.add(new JPanel());
                popup.setPopupSize(new Dimension(240, 150));
                popup.show(invoker, 0, invoker.getHeight());

                Window ownerWindow = SwingUtilities.getWindowAncestor(invoker);
                Window popupWindow = SwingUtilities.getWindowAncestor(popup);
                assertNotNull(popupWindow);
                assertNotSame(ownerWindow, popupWindow);
                @Nullable Shape popupShape = popupWindow.getShape();
                assertNotNull(popupShape);
                assertFalse(popupShape.contains(0.5, 0.5));
                assertTrue(popupShape.contains(popupWindow.getWidth() / 2.0, 1.0));

                popup.setVisible(false);
                assertNull(popupWindow.getShape());
            } finally {
                popup.setVisible(false);
                frame.dispose();
            }
        });
    }

    /// Paints one configured popup into a transparent image.
    ///
    /// @param popup popup to paint
    /// @return rendered pixels
    private static BufferedImage render(RoundedPopupMenu popup) {
        BufferedImage image = new BufferedImage(
                popup.getWidth(),
                popup.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            popup.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
