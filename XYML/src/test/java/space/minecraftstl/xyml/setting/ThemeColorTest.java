// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
        assertEquals(new ThemeColor("blue", "#5555FF"), ThemeColor.of("blue"));
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
