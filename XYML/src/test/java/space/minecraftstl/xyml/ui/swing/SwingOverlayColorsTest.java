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
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies component-surface contrast overlays.
@NotNullByDefault
public final class SwingOverlayColorsTest {
    /// Selects black on light surfaces and white on dark surfaces.
    @Test
    public void followsComponentSurfaceBrightness() {
        JPanel component = new JPanel();
        component.setBackground(Color.WHITE);
        Color light = SwingOverlayColors.contrastOverlay(component);
        component.setBackground(Color.BLACK);
        Color dark = SwingOverlayColors.contrastOverlay(component);
        assertAll(
                () -> assertChannels(Color.BLACK, light),
                () -> assertChannels(Color.WHITE, dark),
                () -> assertEquals(72, light.getAlpha()),
                () -> assertEquals(72, dark.getAlpha()));
    }

    /// Falls back to the button surface when the component has no background.
    @Test
    public void fallsBackToButtonBackground() {
        @Nullable Color previous = UIManager.getColor("Button.background");
        try {
            JPanel component = new JPanel();
            component.setBackground(null);
            UIManager.put("Button.background", Color.WHITE);
            Color light = SwingOverlayColors.contrastOverlay(component);
            UIManager.put("Button.background", Color.BLACK);
            Color dark = SwingOverlayColors.contrastOverlay(component);
            assertAll(
                    () -> assertChannels(Color.BLACK, light),
                    () -> assertChannels(Color.WHITE, dark));
        } finally {
            if (previous == null) {
                UIManager.getDefaults().remove("Button.background");
            } else {
                UIManager.put("Button.background", previous);
            }
        }
    }

    /// Asserts one expected opaque RGB value against an overlay color.
    ///
    /// @param expected expected opaque color
    /// @param actual actual translucent overlay
    private static void assertChannels(Color expected, Color actual) {
        assertEquals(expected.getRGB() & 0x00FFFFFF, actual.getRGB() & 0x00FFFFFF);
    }
}
