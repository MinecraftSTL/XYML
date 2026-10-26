// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies startup text-antialiasing mapping and explicit JVM-property precedence.
@NotNullByDefault
public final class SwingFontAntialiasingTest {
    /// LCD and grayscale preferences map to the established AWT property values.
    @Test
    public void mapsConfiguredModesToAwtValues() {
        Properties lcd = new Properties();
        Properties gray = new Properties();
        Properties automatic = new Properties();

        assertTrue(SwingFontAntialiasing.applyTo(lcd, FontAntialiasingMode.LCD));
        assertTrue(SwingFontAntialiasing.applyTo(gray, FontAntialiasingMode.GRAY));
        assertFalse(SwingFontAntialiasing.applyTo(automatic, FontAntialiasingMode.AUTO));

        assertEquals("lcd", lcd.getProperty("awt.useSystemAAFontSettings"));
        assertEquals("on", gray.getProperty("awt.useSystemAAFontSettings"));
        assertNull(automatic.getProperty("awt.useSystemAAFontSettings"));
    }

    /// An explicit JVM value always takes precedence over the persisted launcher preference.
    @Test
    public void preservesExplicitJvmProperty() {
        Properties properties = new Properties();
        properties.setProperty("awt.useSystemAAFontSettings", "off");

        assertFalse(SwingFontAntialiasing.applyTo(properties, FontAntialiasingMode.LCD));
        assertEquals("off", properties.getProperty("awt.useSystemAAFontSettings"));
    }
}
