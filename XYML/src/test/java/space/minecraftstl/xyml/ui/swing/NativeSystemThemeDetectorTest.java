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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies platform appearance parsing and headless-safe failure fallback without creating native windows.
@NotNullByDefault
public final class NativeSystemThemeDetectorTest {
    /// Windows uses numeric zero for dark applications and treats every unavailable value as light.
    @Test
    public void interpretsWindowsAppsUseLightTheme() {
        assertAll(
                () -> assertTrue(NativeSystemThemeDetector.isWindowsThemeDark(0)),
                () -> assertTrue(NativeSystemThemeDetector.isWindowsThemeDark(0L)),
                () -> assertFalse(NativeSystemThemeDetector.isWindowsThemeDark(1)),
                () -> assertFalse(NativeSystemThemeDetector.isWindowsThemeDark("0")),
                () -> assertFalse(NativeSystemThemeDetector.isWindowsThemeDark(null)));
    }

    /// macOS recognizes `Dark` case-insensitively while an absent global default remains light.
    @Test
    public void interpretsMacOsAppleInterfaceStyle() {
        assertAll(
                () -> assertTrue(NativeSystemThemeDetector.isMacOsThemeDark("Dark")),
                () -> assertTrue(NativeSystemThemeDetector.isMacOsThemeDark(" dark ")),
                () -> assertFalse(NativeSystemThemeDetector.isMacOsThemeDark("Light")),
                () -> assertFalse(NativeSystemThemeDetector.isMacOsThemeDark(null)));
    }

    /// Explicit GTK preferences take precedence over broader desktop hints.
    @Test
    public void prioritizesLinuxToolkitPreferences() {
        assertTrue(NativeSystemThemeDetector.isLinuxThemeDark(
                Map.of(
                        "GTK_THEME", "Adwaita:dark",
                        "XDG_CURRENT_DESKTOP", "Example-Light"),
                ignored -> null));

        assertFalse(NativeSystemThemeDetector.isLinuxThemeDark(
                Map.of(
                        "GTK_APPLICATION_PREFER_DARK_THEME", "false",
                        "GTK_THEME", "Adwaita-dark"),
                ignored -> null));
    }

    /// KDE, Qt, and AWT desktop signals are honored when GTK has no explicit preference.
    @Test
    public void readsLinuxDesktopFallbacks() {
        assertAll(
                () -> assertTrue(NativeSystemThemeDetector.isLinuxThemeDark(
                        Map.of("KDE_COLOR_SCHEME", "Breeze Dark"),
                        ignored -> null)),
                () -> assertTrue(NativeSystemThemeDetector.isLinuxThemeDark(
                        Map.of("QT_STYLE_OVERRIDE", "kvantum-night"),
                        ignored -> null)),
                () -> assertTrue(NativeSystemThemeDetector.isLinuxThemeDark(
                        Map.of(),
                        name -> "gnome.Net/ThemeName".equals(name) ? "Yaru-dark" : null)),
                () -> assertFalse(NativeSystemThemeDetector.isLinuxThemeDark(
                        Map.of("XDG_CURRENT_DESKTOP", "GNOME"),
                        ignored -> null)));
    }

    /// XDG portal output is parsed as a three-state preference without depending on whitespace layout.
    @Test
    public void parsesXdgPortalColorScheme() {
        assertAll(
                () -> assertTrue(NativeSystemThemeDetector.parsePortalColorScheme(
                        "variant       uint32 1\n").orElseThrow()),
                () -> assertFalse(NativeSystemThemeDetector.parsePortalColorScheme(
                        "variant uint32 2").orElseThrow()),
                () -> assertEquals(Optional.empty(),
                        NativeSystemThemeDetector.parsePortalColorScheme("variant uint32 0")),
                () -> assertEquals(Optional.empty(),
                        NativeSystemThemeDetector.parsePortalColorScheme("malformed")));
    }

    /// A transient read failure preserves the last explicit appearance instead of flashing to light.
    @Test
    public void retainsLastThemeAcrossTransientFailure() {
        AtomicBoolean fail = new AtomicBoolean();
        NativeSystemThemeDetector detector = new NativeSystemThemeDetector(() -> {
            if (fail.get()) {
                throw new IllegalStateException("temporary native failure");
            }
            return Optional.of(true);
        });

        assertTrue(detector.isDarkTheme());
        fail.set(true);
        assertTrue(detector.isDarkTheme());
    }

    /// A failing native reader cannot escape into Swing and deterministically falls back to light.
    @Test
    public void containsHeadlessAndNativeDetectionFailures() {
        NativeSystemThemeDetector detector = new NativeSystemThemeDetector(() -> {
            throw new IllegalStateException("native appearance unavailable");
        });

        assertAll(
                () -> assertFalse(assertDoesNotThrow(detector::isDarkTheme)),
                () -> assertFalse(assertDoesNotThrow(detector::isDarkTheme)));
    }

    /// The real current-platform detector is callable without creating any Swing or AWT window.
    @Test
    public void readsCurrentPlatformWithoutWindowCreation() {
        NativeSystemThemeDetector detector = NativeSystemThemeDetector.create();

        assertDoesNotThrow(detector::isDarkTheme);
    }
}
