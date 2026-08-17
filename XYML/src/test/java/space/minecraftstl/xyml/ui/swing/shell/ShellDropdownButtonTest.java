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
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JPopupMenu;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that collapsed shell selectors reveal the wallpaper without losing interaction feedback.
@NotNullByDefault
public final class ShellDropdownButtonTest {
    /// Distinct background pixel used for exact off-screen paint comparison.
    private static final int BACKDROP_ARGB = new Color(0x31, 0xA9, 0xE1).getRGB();

    /// The collapsed state keeps a visible outline while rollover and pressed feedback remain visible.
    @Test
    public void keepsTransparentSurfaceOutlinedAcrossInteractionStates() {
        EdtDispatcher.executeAndWait(() -> {
            for (boolean dark : List.of(false, true)) {
                assertTrue(dark ? FlatDarkLaf.setup() : FlatLightLaf.setup());
                ShellDropdownButton button = new ShellDropdownButton();
                button.setText("Account");
                button.setSize(220, 36);

                BufferedImage resting = render(button);
                button.getModel().setRollover(true);
                BufferedImage rollover = render(button);
                button.getModel().setRollover(false);
                button.getModel().setArmed(true);
                button.getModel().setPressed(true);
                BufferedImage pressed = render(button);
                button.getModel().setPressed(false);
                button.getModel().setArmed(false);
                button.setEnabled(false);
                BufferedImage disabled = render(button);

                assertAll(
                        () -> assertFalse(button.isOpaque()),
                        () -> assertTrue(button.isContentAreaFilled()),
                        () -> assertEquals(0, button.getBackground().getAlpha()),
                        () -> assertNull(button.getClientProperty(FlatClientProperties.BUTTON_TYPE)),
                        () -> assertEquals(BACKDROP_ARGB, centerPixel(resting)),
                        () -> assertTrue(hasVisibleHorizontalOutline(resting)),
                        () -> assertTrue(differingPixelCount(resting, rollover) > 0),
                        () -> assertTrue(differingPixelCount(resting, pressed) > 0),
                        () -> assertEquals(BACKDROP_ARGB, centerPixel(disabled)));
            }
            FlatLightLaf.setup();
        });
    }

    /// A popup auto-hidden during an invoker click stays closed when the button action fires on release.
    @Test
    public void keepsAutomaticallyDismissedPopupClosed() {
        EdtDispatcher.executeAndWait(() -> {
            ShellDropdownButton button = new ShellDropdownButton();
            TestPopupMenu popup = new TestPopupMenu();
            AtomicInteger openCount = new AtomicInteger();
            int initialUpdateCount = popup.updateCount();
            button.setSize(220, 36);
            button.bindPopup(popup, () -> {
                openCount.incrementAndGet();
                popup.setVisible(true);
            });

            button.doClick();
            assertAll(
                    () -> assertTrue(popup.isVisible()),
                    () -> assertEquals(1, openCount.get()),
                    () -> assertEquals(initialUpdateCount + 1, popup.updateCount()));

            popup.setVisible(false);
            button.processMouseEvent(mouseEvent(button, MouseEvent.MOUSE_PRESSED));
            button.processMouseEvent(mouseEvent(button, MouseEvent.MOUSE_RELEASED));

            assertAll(
                    () -> assertFalse(popup.isVisible()),
                    () -> assertEquals(1, openCount.get()));
        });
    }

    /// The disclosure chevron points down while collapsed and up while the bound popup is visible.
    @Test
    public void pointsDisclosureChevronTowardPopupState() {
        EdtDispatcher.executeAndWait(() -> {
            assertTrue(FlatLightLaf.setup());
            ShellDropdownButton button = new ShellDropdownButton();
            TestPopupMenu popup = new TestPopupMenu();
            button.setSize(220, 36);
            button.bindPopup(popup, () -> popup.setVisible(true));

            BufferedImage collapsed = render(button);
            popup.setVisible(true);
            BufferedImage expanded = render(button);
            popup.setVisible(false);
            BufferedImage collapsedAgain = render(button);
            int centerX = button.getWidth() - 15;
            int centerY = button.getHeight() / 2;
            Color foreground = button.getForeground();

            assertAll(
                    () -> assertTrue(colorDistance(collapsed.getRGB(centerX, centerY + 2), foreground)
                            < colorDistance(collapsed.getRGB(centerX, centerY - 2), foreground)),
                    () -> assertTrue(colorDistance(expanded.getRGB(centerX, centerY - 2), foreground)
                            < colorDistance(expanded.getRGB(centerX, centerY + 2), foreground)),
                    () -> assertEquals(0, differingPixelCount(collapsed, collapsedAgain)));
        });
    }

    /// Creates one primary-button event inside the dropdown bounds.
    ///
    /// @param button event target
    /// @param eventId pressed or released event identifier
    /// @return configured mouse event
    private static MouseEvent mouseEvent(ShellDropdownButton button, int eventId) {
        return new MouseEvent(
                button,
                eventId,
                System.currentTimeMillis(),
                0,
                button.getWidth() / 2,
                button.getHeight() / 2,
                1,
                false,
                MouseEvent.BUTTON1);
    }

    /// Paints one selector over a known opaque background.
    ///
    /// @param button configured selector button
    /// @return rendered selector pixels
    private static BufferedImage render(ShellDropdownButton button) {
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
        return image;
    }

    /// Returns the exact center pixel from a rendered selector.
    ///
    /// @param image rendered selector pixels
    /// @return center ARGB pixel
    private static int centerPixel(BufferedImage image) {
        return image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
    }

    /// Measures RGB distance from one rendered pixel to a reference color.
    ///
    /// @param argb rendered pixel
    /// @param reference comparison color
    /// @return summed absolute RGB channel distance
    private static int colorDistance(int argb, Color reference) {
        Color pixel = new Color(argb, true);
        return Math.abs(pixel.getRed() - reference.getRed())
                + Math.abs(pixel.getGreen() - reference.getGreen())
                + Math.abs(pixel.getBlue() - reference.getBlue());
    }

    /// Counts pixels changed by one interaction-state transition.
    ///
    /// @param first first rendered state
    /// @param second second rendered state
    /// @return number of pixels whose ARGB values differ
    private static int differingPixelCount(BufferedImage first, BufferedImage second) {
        int differences = 0;
        for (int y = 0; y < first.getHeight(); ++y) {
            for (int x = 0; x < first.getWidth(); ++x) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) {
                    ++differences;
                }
            }
        }
        return differences;
    }

    /// Detects theme-border pixels in the horizontal edge bands away from rounded corners.
    ///
    /// @param image rendered selector pixels
    /// @return whether the resting selector has a visible outline
    private static boolean hasVisibleHorizontalOutline(BufferedImage image) {
        int bandHeight = Math.min(6, image.getHeight() / 2);
        for (int y = 0; y < bandHeight; ++y) {
            for (int x = 8; x < image.getWidth() - 8; ++x) {
                if (image.getRGB(x, y) != BACKDROP_ARGB
                        || image.getRGB(x, image.getHeight() - 1 - y) != BACKDROP_ARGB) {
                    return true;
                }
            }
        }
        return false;
    }

    /// Headless popup fake that publishes the same visibility callbacks as `JPopupMenu`.
    @NotNullByDefault
    private static final class TestPopupMenu extends JPopupMenu {
        /// Simulated popup visibility.
        private boolean popupVisible;

        /// Look-and-feel refreshes observed by this popup.
        private int updates;

        /// Records an explicit refresh after construction.
        @Override
        public void updateUI() {
            super.updateUI();
            updates++;
        }

        /// Returns simulated visibility without creating a native popup window.
        ///
        /// @return whether the fake popup is visible
        @Override
        public boolean isVisible() {
            return popupVisible;
        }

        /// Publishes visibility callbacks without requiring a displayable invoker.
        ///
        /// @param visible requested popup visibility
        @Override
        public void setVisible(boolean visible) {
            if (popupVisible == visible) {
                return;
            }
            if (visible) {
                firePopupMenuWillBecomeVisible();
            } else {
                firePopupMenuWillBecomeInvisible();
            }
            popupVisible = visible;
        }

        /// Returns all observed look-and-feel refreshes.
        ///
        /// @return update count
        private int updateCount() {
            return updates;
        }
    }
}
