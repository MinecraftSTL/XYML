// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.log;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.awt.Font;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies persisted game-log font normalization without loading global settings.
@NotNullByDefault
public final class SwingLogFontPreferencesTest {
    /// Explicit local family and fractional size are preserved exactly by the AWT resolver.
    @Test
    public void resolvesConfiguredFamilyAndSize() {
        Font font = SwingLogFontPreferences.resolve(Font.SERIF, 15.5);

        assertEquals(Font.SERIF, font.getFamily());
        assertEquals(15.5F, font.getSize2D());
    }

    /// Blank families use monospaced text and malformed sizes use the persisted legacy default.
    @Test
    public void normalizesDefaultFamilyAndInvalidSize() {
        Font font = SwingLogFontPreferences.resolve("  ", Double.NaN);

        assertEquals(Font.MONOSPACED, font.getFamily());
        assertEquals(12.0F, font.getSize2D());
    }
}
