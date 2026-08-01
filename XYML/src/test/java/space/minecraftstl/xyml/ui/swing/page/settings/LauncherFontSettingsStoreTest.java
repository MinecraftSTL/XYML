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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.UserSettings;
import space.minecraftstl.xyml.ui.swing.FontAntialiasingMode;
import space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies font-store normalization and independent launcher/user write permissions.
@NotNullByDefault
final class LauncherFontSettingsStoreTest {
    /// Malformed persisted font values are normalized for display without rewriting their source properties.
    @Test
    void normalizesHistoricalValuesWithoutMutatingStorage() {
        LauncherStateDispatcher.executeAndWait(() -> {
            LauncherSettings launcherSettings = new LauncherSettings();
            UserSettings userSettings = new UserSettings();
            launcherSettings.launcherFontFamilyProperty().set("   ");
            launcherSettings.logFontFamilyProperty().set("\t");
            launcherSettings.logFontSizeProperty().set(Double.NaN);
            userSettings.fontAntiAliasingProperty().set("GRAY");

            LauncherFontSettingsStore store = new LauncherFontSettingsStore(
                    launcherSettings,
                    userSettings,
                    () -> true,
                    () -> true);
            try {
                FontSettingsSnapshot snapshot = store.snapshot();
                assertAll(
                        () -> assertNull(snapshot.launcherFontFamily()),
                        () -> assertNull(snapshot.logFontFamily()),
                        () -> assertEquals(12.0, snapshot.logFontSize()),
                        () -> assertEquals(FontAntialiasingMode.GRAY, snapshot.antialiasingMode()),
                        () -> assertEquals("   ", launcherSettings.launcherFontFamilyProperty().get()),
                        () -> assertTrue(Double.isNaN(launcherSettings.logFontSizeProperty().get())));

                store.setLauncherFontFamily("  Serif  ");
                store.setLogFontFamily("  Monospaced  ");
                store.setLogFontSize(15.5);
                FontSettingsSnapshot updated = store.snapshot();
                assertAll(
                        () -> assertEquals("Serif", updated.launcherFontFamily()),
                        () -> assertEquals("Monospaced", updated.logFontFamily()),
                        () -> assertEquals(15.5, updated.logFontSize()));
            } finally {
                store.close();
            }
        });
    }

    /// Launcher and per-user settings honor their separate dynamic write permissions.
    @Test
    void separatesLauncherAndUserWritePermissions() {
        LauncherStateDispatcher.executeAndWait(() -> {
            LauncherSettings launcherSettings = new LauncherSettings();
            UserSettings userSettings = new UserSettings();
            LauncherFontSettingsStore store = new LauncherFontSettingsStore(
                    launcherSettings,
                    userSettings,
                    () -> false,
                    () -> true);
            try {
                store.setLauncherFontFamily("Serif");
                store.setLogFontFamily("Monospaced");
                store.setLogFontSize(18.0);
                store.setAntialiasingMode(FontAntialiasingMode.LCD);

                FontSettingsSnapshot snapshot = store.snapshot();
                assertAll(
                        () -> assertNull(launcherSettings.launcherFontFamilyProperty().get()),
                        () -> assertNull(launcherSettings.logFontFamilyProperty().get()),
                        () -> assertEquals(12.0, launcherSettings.logFontSizeProperty().get()),
                        () -> assertEquals("lcd", userSettings.fontAntiAliasingProperty().get()),
                        () -> assertFalse(snapshot.launcherSettingsWritable()),
                        () -> assertTrue(snapshot.userSettingsWritable()),
                        () -> assertEquals(FontAntialiasingMode.LCD, snapshot.antialiasingMode()));
            } finally {
                store.close();
            }
        });
    }
}
