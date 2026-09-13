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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import javax.swing.UIManager;
import java.awt.Color;
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

    /// Uses black in a light theme and white in a dark theme for the translucent progress fill.
    @Test
    public void followsThemeBrightnessForProgressFill() {
        @Nullable Color previousSurface = UIManager.getColor("Panel.background");
        try {
            UIManager.put("Panel.background", Color.WHITE);
            Color lightFill = ShellNavigationButton.progressFillColor();
            UIManager.put("Panel.background", Color.BLACK);
            Color darkFill = ShellNavigationButton.progressFillColor();
            assertAll(
                    () -> assertEquals(Color.BLACK.getRGB() & 0x00FFFFFF, lightFill.getRGB() & 0x00FFFFFF),
                    () -> assertEquals(Color.WHITE.getRGB() & 0x00FFFFFF, darkFill.getRGB() & 0x00FFFFFF),
                    () -> assertEquals(72, lightFill.getAlpha()),
                    () -> assertEquals(72, darkFill.getAlpha()));
        } finally {
            if (previousSurface == null) {
                UIManager.getDefaults().remove("Panel.background");
            } else {
                UIManager.put("Panel.background", previousSurface);
            }
        }
    }

}
