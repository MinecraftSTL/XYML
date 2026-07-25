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
