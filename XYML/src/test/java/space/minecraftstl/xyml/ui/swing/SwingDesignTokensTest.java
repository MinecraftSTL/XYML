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

import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.icons.FlatCheckBoxIcon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.setting.LauncherSettings;

import javax.swing.JCheckBox;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests validation and FlatLaf mapping for adjustable Swing design tokens.
@NotNullByDefault
public final class SwingDesignTokensTest {
    /// Invalid radii are rejected instead of being silently normalized or overflowing their arc diameter.
    @Test
    public void cornerRadiusMustNotBeNegative() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new SwingDesignTokens(-1)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new SwingDesignTokens(Integer.MAX_VALUE / 2 + 1)));
    }

    /// One configured radius becomes an arc diameter for every supported FlatLaf key.
    @Test
    public void appliesCornerRadiusToFlatLafDefaults() {
        UIDefaults defaults = new UIDefaults();
        new SwingDesignTokens(5).applyTo(defaults);

        assertAll(
                () -> assertEquals(10, defaults.getInt("Component.arc")),
                () -> assertEquals(10, defaults.getInt("Button.arc")),
                () -> assertEquals(6, defaults.getInt("CheckBox.arc")),
                () -> assertEquals(10, defaults.getInt("TextComponent.arc")),
                () -> assertEquals(10, defaults.getInt("ProgressBar.arc")),
                () -> assertEquals(10, defaults.getInt("ScrollBar.thumbArc")),
                () -> assertEquals(10, defaults.getInt("ScrollBar.trackArc")));
    }

    /// FlatLaf's default checkbox icon reads the bounded dedicated arc at the largest launcher setting.
    @Test
    public void limitsCornerRadiusForFlatLafCheckBoxIcon() {
        UIDefaults defaults = UIManager.getDefaults();
        @Nullable Object previousArc = defaults.get("CheckBox.arc");
        try {
            new SwingDesignTokens(LauncherSettings.MAXIMUM_CORNER_RADIUS).applyTo(defaults);

            assertEquals(6, new FlatCheckBoxIcon().getStyleableValue("arc"));
        } finally {
            if (previousArc == null) {
                defaults.remove("CheckBox.arc");
            } else {
                defaults.put("CheckBox.arc", previousArc);
            }
        }
    }

    /// Checkbox painting stays a visible rounded square at the largest launcher radius.
    @Test
    public void largeCornerRadiusRendersWithinCheckBoxBounds() {
        assertTrue(FlatLightLaf.setup());
        FlatCheckBoxIcon dimensionProbe = new FlatCheckBoxIcon();
        int sideLength = Math.min(dimensionProbe.getIconWidth(), dimensionProbe.getIconHeight());

        assertTrue(LauncherSettings.MAXIMUM_CORNER_RADIUS >= sideLength);
        assertCheckBoxStatesRenderWithinBounds(LauncherSettings.MAXIMUM_CORNER_RADIUS, 6);
    }

    /// Radius changes create a new immutable token value without changing the original.
    @Test
    public void withCornerRadiusReturnsNewTokens() {
        SwingDesignTokens original = new SwingDesignTokens(3);

        assertEquals(3, original.cornerRadius());
        assertEquals(9, original.withCornerRadius(9).cornerRadius());
    }

    /// Paints representative checkbox states and checks that the bounded arc remains effective.
    ///
    /// @param requestedRadius requested component radius
    /// @param expectedCheckBoxArc bounded checkbox arc
    private static void assertCheckBoxStatesRenderWithinBounds(int requestedRadius, int expectedCheckBoxArc) {
        UIDefaults defaults = UIManager.getDefaults();
        @Nullable Object previousArc = defaults.get("CheckBox.arc");
        try {
            new SwingDesignTokens(requestedRadius).applyTo(defaults);
            FlatCheckBoxIcon icon = new FlatCheckBoxIcon();

            assertEquals(expectedCheckBoxArc, icon.getStyleableValue("arc"));
            assertRenderedWithinBounds(renderCheckBoxIcon(icon, false, true), icon);
            assertRenderedWithinBounds(renderCheckBoxIcon(icon, true, true), icon);
            assertRenderedWithinBounds(renderCheckBoxIcon(icon, true, false), icon);
        } finally {
            if (previousArc == null) {
                defaults.remove("CheckBox.arc");
            } else {
                defaults.put("CheckBox.arc", previousArc);
            }
        }
    }

    /// Renders one checkbox icon with transparent padding so out-of-bounds painting remains observable.
    private static BufferedImage renderCheckBoxIcon(FlatCheckBoxIcon icon, boolean selected, boolean enabled) {
        int padding = 4;
        BufferedImage image = new BufferedImage(
                icon.getIconWidth() + padding * 2,
                icon.getIconHeight() + padding * 2,
                BufferedImage.TYPE_INT_ARGB);
        JCheckBox checkBox = new JCheckBox();
        checkBox.setSelected(selected);
        checkBox.setEnabled(enabled);
        Graphics2D graphics = image.createGraphics();
        try {
            icon.paintIcon(checkBox, graphics, padding, padding);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /// Verifies that rendering is nonempty and does not touch pixels outside the icon's declared bounds.
    private static void assertRenderedWithinBounds(BufferedImage image, FlatCheckBoxIcon icon) {
        int padding = 4;
        int visiblePixels = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                boolean insideIcon = x >= padding
                        && x < padding + icon.getIconWidth()
                        && y >= padding
                        && y < padding + icon.getIconHeight();
                if (insideIcon && alpha > 0) {
                    visiblePixels++;
                } else if (!insideIcon) {
                    assertEquals(0, alpha, "Checkbox icon painted outside its declared bounds at " + x + "," + y);
                }
            }
        }

        assertTrue(visiblePixels > 0, "Checkbox icon must remain visible");
        assertTrue(
                visiblePixels < icon.getIconWidth() * icon.getIconHeight(),
                "Rounded checkbox icon must retain transparent pixels");
    }

}
