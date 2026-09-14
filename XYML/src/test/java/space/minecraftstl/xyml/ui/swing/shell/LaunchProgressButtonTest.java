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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import com.formdev.flatlaf.FlatDarkLaf;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the compact horizontal progress state used by the ordinary launch button.
@NotNullByDefault
public final class LaunchProgressButtonTest {
    /// The strip remains hidden until the ordinary launch command becomes active.
    @Test
    public void hidesProgressUntilLaunchStarts() {
        LaunchProgressButton button = new LaunchProgressButton();

        assertAll(
                () -> assertFalse(button.isProgressVisible()),
                () -> assertEquals(0, button.filledProgressWidth(100)));

        button.setProgressVisible(true);
        button.setProgress(OptionalDouble.of(0.5));

        assertAll(
                () -> assertTrue(button.isProgressVisible()),
                () -> assertEquals(50, button.filledProgressWidth(100)));
    }

    /// Indeterminate progress does not invent a fake fraction, while known progress fills left to right.
    @Test
    public void representsIndeterminateAndCompleteProgress() {
        LaunchProgressButton button = new LaunchProgressButton();
        button.setProgressVisible(true);

        button.setProgress(OptionalDouble.empty());
        assertEquals(0, button.filledProgressWidth(100));

        button.setProgress(OptionalDouble.of(1.0));
        assertEquals(100, button.filledProgressWidth(100));
    }

    /// Paints a visible white fill from the left edge toward the right in dark mode.
    @Test
    public void paintsLeftToRightFillInDarkTheme() {
        assertTrue(FlatDarkLaf.setup());
        LaunchProgressButton button = new LaunchProgressButton();
        button.setSize(200, 36);
        button.setProgressVisible(true);
        button.setProgress(OptionalDouble.of(0.5));

        BufferedImage rendered = new BufferedImage(200, 36, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = rendered.createGraphics();
        try {
            button.paint(graphics);
        } finally {
            graphics.dispose();
        }

        assertAll(
                () -> assertTrue(button.filledProgressWidth(200) > 0),
                () -> assertTrue(rendered.getRGB(160, 18) != rendered.getRGB(40, 18)));
    }

    /// Uses black in a light theme and white in a dark theme for the translucent progress fill.
    @Test
    public void followsThemeBrightnessForProgressFill() {
        LaunchProgressButton button = new LaunchProgressButton();
        button.setBackground(Color.WHITE);
        Color lightFill = ProgressOverlayColors.fillColor(button);
        button.setBackground(Color.BLACK);
        Color darkFill = ProgressOverlayColors.fillColor(button);
        assertAll(
                () -> assertEquals(Color.BLACK.getRGB() & 0x00FFFFFF, lightFill.getRGB() & 0x00FFFFFF),
                () -> assertEquals(Color.WHITE.getRGB() & 0x00FFFFFF, darkFill.getRGB() & 0x00FFFFFF),
                () -> assertEquals(72, lightFill.getAlpha()),
                () -> assertEquals(72, darkFill.getAlpha()));
    }

}
