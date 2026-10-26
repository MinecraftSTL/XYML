// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
