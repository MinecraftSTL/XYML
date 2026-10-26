// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
