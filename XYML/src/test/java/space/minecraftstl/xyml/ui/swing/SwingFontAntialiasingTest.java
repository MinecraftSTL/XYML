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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.page.settings.FontAntialiasingMode;

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
