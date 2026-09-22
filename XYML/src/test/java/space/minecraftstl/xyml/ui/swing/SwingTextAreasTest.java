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

import javax.swing.JTextArea;
import java.awt.Dimension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies shared wrapping text sizing and configuration.
@NotNullByDefault
class SwingTextAreasTest {
    /// Keeps token values wrapped by character and reserves a sixteen-character minimum width.
    @Test
    void wrapsTokensAtCharacterBoundaries() {
        JTextArea area = SwingTextAreas.wrappingToken();
        area.setText("x".repeat(200));
        area.setSize(new Dimension(80, Short.MAX_VALUE));

        assertTrue(area.getLineWrap());
        assertFalse(area.getWrapStyleWord());
        assertTrue(area.getPreferredSize().height > area.getFontMetrics(area.getFont()).getHeight());
        assertTrue(area.getMinimumSize().width >= area.getFontMetrics(area.getFont()).charWidth('W') * 16);
    }

    /// Keeps human-readable values wrapped at word boundaries.
    @Test
    void wrapsValuesAtWordBoundaries() {
        JTextArea area = SwingTextAreas.wrappingValue();

        assertTrue(area.getLineWrap());
        assertTrue(area.getWrapStyleWord());
        assertFalse(area.isEditable());
        assertFalse(area.isOpaque());
    }
}
