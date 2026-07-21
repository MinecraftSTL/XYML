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

import javax.swing.UIDefaults;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests validation and FlatLaf mapping for adjustable Swing design tokens.
@NotNullByDefault
public final class SwingDesignTokensTest {
    /// Negative radii are rejected instead of being silently normalized.
    @Test
    public void cornerRadiusMustNotBeNegative() {
        assertThrows(IllegalArgumentException.class, () -> new SwingDesignTokens(-1));
    }

    /// One configured radius is applied consistently to every supported FlatLaf arc key.
    @Test
    public void appliesCornerRadiusToFlatLafDefaults() {
        UIDefaults defaults = new UIDefaults();
        new SwingDesignTokens(11).applyTo(defaults);

        assertAll(
                () -> assertEquals(11, defaults.getInt("Component.arc")),
                () -> assertEquals(11, defaults.getInt("Button.arc")),
                () -> assertEquals(11, defaults.getInt("TextComponent.arc")),
                () -> assertEquals(11, defaults.getInt("ProgressBar.arc")),
                () -> assertEquals(11, defaults.getInt("ScrollBar.thumbArc")),
                () -> assertEquals(11, defaults.getInt("ScrollBar.trackArc")));
    }

    /// Radius changes create a new immutable token value without changing the original.
    @Test
    public void withCornerRadiusReturnsNewTokens() {
        SwingDesignTokens original = new SwingDesignTokens(3);

        assertEquals(3, original.cornerRadius());
        assertEquals(9, original.withCornerRadius(9).cornerRadius());
    }
}
