// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.theme;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Tests for built-in launcher wallpapers.
@NotNullByDefault
public final class BuiltinBackgroundTest {
    /// Checks stable wallpaper IDs, accent colors, and fallback lookup behavior.
    @Test
    public void exposesStableConfigurationValues() {
        assertEquals(
                List.of("2021-08-26", "2016-02-25", "2015-06-22"),
                BuiltinBackground.BUILTIN_BACKGROUND_IDS);
        assertEquals("#3F6AA2", BuiltinBackground.WALLPAPER_2021_08_26.themeColor().color());
        assertEquals("#354264", BuiltinBackground.WALLPAPER_2016_02_25.themeColor().color());
        assertEquals("#FBC578", BuiltinBackground.WALLPAPER_2015_06_22.themeColor().color());
        assertSame(
                BuiltinBackground.WALLPAPER_2016_02_25,
                BuiltinBackground.fromId("2016-02-25"));
        assertNull(BuiltinBackground.fromId("missing"));
        assertSame(BuiltinBackground.FALLBACK, BuiltinBackground.fromIdOrFallback("missing"));
    }
}
