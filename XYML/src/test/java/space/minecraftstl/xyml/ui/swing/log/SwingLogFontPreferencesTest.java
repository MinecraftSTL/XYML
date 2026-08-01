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
