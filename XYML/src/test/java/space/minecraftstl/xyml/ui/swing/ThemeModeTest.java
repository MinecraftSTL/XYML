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

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests deterministic resolution of explicit and system theme modes.
@NotNullByDefault
public final class ThemeModeTest {
    /// Explicit modes do not query the operating-system detector.
    @Test
    public void explicitModesIgnoreSystemDetector() {
        AtomicInteger calls = new AtomicInteger();
        SystemThemeDetector detector = () -> {
            calls.incrementAndGet();
            return true;
        };

        assertAll(
                () -> assertEquals(ThemeVariant.LIGHT, ThemeMode.LIGHT.resolve(detector)),
                () -> assertEquals(ThemeVariant.DARK, ThemeMode.DARK.resolve(detector)),
                () -> assertEquals(0, calls.get()));
    }

    /// System mode follows both light and dark detector results.
    @Test
    public void systemModeUsesDetector() {
        assertAll(
                () -> assertEquals(ThemeVariant.LIGHT, ThemeMode.SYSTEM.resolve(() -> false)),
                () -> assertEquals(ThemeVariant.DARK, ThemeMode.SYSTEM.resolve(() -> true)));
    }

    /// Persisted legacy and canonical identifiers round-trip without changing automatic behavior.
    @Test
    public void mapsPersistedSettingValues() {
        assertAll(
                () -> assertEquals(ThemeMode.SYSTEM, ThemeMode.fromSettingValue(null)),
                () -> assertEquals(ThemeMode.SYSTEM, ThemeMode.fromSettingValue("auto")),
                () -> assertEquals(ThemeMode.SYSTEM, ThemeMode.fromSettingValue("SYSTEM")),
                () -> assertEquals(ThemeMode.SYSTEM, ThemeMode.fromSettingValue("unknown")),
                () -> assertEquals(ThemeMode.LIGHT, ThemeMode.fromSettingValue(" light ")),
                () -> assertEquals(ThemeMode.DARK, ThemeMode.fromSettingValue("DARK")),
                () -> assertEquals("auto", ThemeMode.SYSTEM.settingValue()),
                () -> assertEquals("light", ThemeMode.LIGHT.settingValue()),
                () -> assertEquals("dark", ThemeMode.DARK.settingValue()));
    }
}
