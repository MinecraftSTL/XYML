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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests reconstruction, persistence, and concrete resolution of all four brightness preferences.
@NotNullByDefault
public final class ThemeBrightnessPreferenceTest {
    /// Override membership distinguishes theme inheritance from the retained raw value.
    @Test
    public void reconstructsPreferenceFromMembershipAndValue() {
        assertAll(
                () -> assertEquals(ThemeBrightnessPreference.THEME,
                        ThemeBrightnessPreference.fromSetting(false, "dark")),
                () -> assertEquals(ThemeBrightnessPreference.SYSTEM,
                        ThemeBrightnessPreference.fromSetting(true, "system")),
                () -> assertEquals(ThemeBrightnessPreference.LIGHT,
                        ThemeBrightnessPreference.fromSetting(true, "light")),
                () -> assertEquals(ThemeBrightnessPreference.DARK,
                        ThemeBrightnessPreference.fromSetting(true, "dark")),
                () -> assertNull(ThemeBrightnessPreference.THEME.settingValue()),
                () -> assertEquals("system", ThemeBrightnessPreference.SYSTEM.settingValue()));
    }

    /// Removed aliases and unknown persisted values fail instead of silently changing brightness semantics.
    @Test
    public void rejectsNonCanonicalValues() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ThemeBrightnessPreference.fromSetting(true, "auto")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ThemeBrightnessPreference.fromSetting(true, "unknown")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ThemeBrightnessPreference.fromSetting(true, null)));
    }

    /// Every preference resolves against theme and system brightness without ambiguity.
    @Test
    public void resolvesEveryPreference() {
        assertAll(
                () -> assertEquals(ThemeBrightness.LIGHT,
                        ThemeBrightnessPreference.THEME.resolve(ThemeBrightness.LIGHT, ThemeBrightness.DARK)),
                () -> assertEquals(ThemeBrightness.DARK,
                        ThemeBrightnessPreference.SYSTEM.resolve(ThemeBrightness.LIGHT, ThemeBrightness.DARK)),
                () -> assertEquals(ThemeBrightness.LIGHT,
                        ThemeBrightnessPreference.LIGHT.resolve(ThemeBrightness.DARK, ThemeBrightness.DARK)),
                () -> assertEquals(ThemeBrightness.DARK,
                        ThemeBrightnessPreference.DARK.resolve(ThemeBrightness.LIGHT, ThemeBrightness.LIGHT)));
    }
}
