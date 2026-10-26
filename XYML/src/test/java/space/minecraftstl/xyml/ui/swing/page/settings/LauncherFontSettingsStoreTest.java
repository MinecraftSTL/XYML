// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
