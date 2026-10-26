// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import com.formdev.flatlaf.FlatDarkLaf;

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

}
