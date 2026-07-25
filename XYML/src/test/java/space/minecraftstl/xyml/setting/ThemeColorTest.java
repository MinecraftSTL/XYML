/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.setting;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.theme.ThemeColor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Tests parsing of toolkit-neutral theme color configuration values.
@NotNullByDefault
public final class ThemeColorTest {
    /// Verifies named colors, hexadecimal colors, and malformed values.
    @Test
    public void testOf() {
        assertEquals(new ThemeColor("#AABBCC", "#AABBCC"), ThemeColor.of("#AABBCC"));
        assertEquals(new ThemeColor("blue", "#5C6BC0"), ThemeColor.of("blue"));
        assertEquals(new ThemeColor("darker_blue", "#283593"), ThemeColor.of("darker_blue"));
        assertEquals(new ThemeColor("green", "#43A047"), ThemeColor.of("green"));
        assertEquals(new ThemeColor("orange", "#E67E22"), ThemeColor.of("orange"));
        assertEquals(new ThemeColor("purple", "#9C27B0"), ThemeColor.of("purple"));
        assertEquals(new ThemeColor("red", "#B71C1C"), ThemeColor.of("red"));

        assertNull(ThemeColor.of((String) null));
        assertNull(ThemeColor.of(""));
        assertNull(ThemeColor.of("unknown"));
    }
}
