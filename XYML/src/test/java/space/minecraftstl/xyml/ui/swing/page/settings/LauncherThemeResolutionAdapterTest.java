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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.setting.BackgroundType;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.ThemeColorType;
import space.minecraftstl.xyml.theme.BuiltinBackground;
import space.minecraftstl.xyml.theme.NetworkBackgroundImageCachePolicy;
import space.minecraftstl.xyml.theme.ThemeBrightness;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.theme.ThemeColor;
import space.minecraftstl.xyml.theme.ThemeColorSource;
import space.minecraftstl.xyml.theme.ThemeColorStyle;
import space.minecraftstl.xyml.theme.ThemeReference;
import space.minecraftstl.xyml.theme.ThemeResolutionRequest;
import space.minecraftstl.xyml.theme.ThemeResolveContext;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests launcher-setting adaptation and atomic appearance persistence.
@NotNullByDefault
public final class LauncherThemeResolutionAdapterTest {
    /// Override membership and source values map to one immutable resolution request.
    @Test
    public void mapsSelectedThemeAndUserOverrides() {
        AtomicReference<@Nullable ThemeResolutionRequest> captured = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            LauncherSettings settings = new LauncherSettings();
            settings.selectedThemeProperty().set(new ThemeReference("xyml.classic", "2015-06-22"));
            settings.themeBrightnessModeProperty().set("dark");
            settings.themeColorTypeProperty().set(ThemeColorType.CUSTOM);
            settings.customThemeColorProperty().set(Objects.requireNonNull(ThemeColor.of("#147D64")));
            settings.themeColorStyleProperty().set("vibrant");
            settings.getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_COLOR);
            settings.getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_COLOR_STYLE);
            captured.set(LauncherThemeResolutionAdapter.snapshot(
                    settings,
                    new ThemeResolveContext(ThemeBrightness.DARK, "windows", "zh")));
        });

        ThemeResolutionRequest request = Objects.requireNonNull(captured.get());
        assertAll(
                () -> assertEquals(new ThemeReference("xyml.classic", "2015-06-22"), request.selectedTheme()),
                () -> assertEquals(ThemeBrightnessPreference.THEME,
                        request.userOverrides().brightnessPreference()),
                () -> assertEquals(
                        ThemeColorSource.custom(Objects.requireNonNull(ThemeColor.of("#147D64"))),
                        request.userOverrides().color()),
                () -> assertEquals(ThemeColorStyle.VIBRANT, request.userOverrides().colorStyle()));
    }

    /// Store writes update the raw value and override key as one externally observable state transition.
    @Test
    public void persistsFourBrightnessStatesAtomically() {
        AtomicReference<@Nullable LauncherAppearanceStore> storeReference = new AtomicReference<>();
        AtomicReference<@Nullable LauncherSettings> settingsReference = new AtomicReference<>();
        AtomicInteger transitions = new AtomicInteger();
        EdtDispatcher.executeAndWait(() -> {
            LauncherSettings settings = new LauncherSettings();
            LauncherAppearanceStore store = new LauncherAppearanceStore(settings, () -> true);
            store.subscribe(change -> transitions.incrementAndGet());
            settingsReference.set(settings);
            storeReference.set(store);

            assertEquals(ThemeBrightnessPreference.THEME, store.snapshot().brightnessPreference());
            store.setThemeBrightnessPreference(ThemeBrightnessPreference.DARK);
        });

        LauncherAppearanceStore store = Objects.requireNonNull(storeReference.get());
        LauncherSettings settings = Objects.requireNonNull(settingsReference.get());
        assertAll(
                () -> assertEquals(ThemeBrightnessPreference.DARK, store.snapshot().brightnessPreference()),
                () -> assertEquals("dark", settings.themeBrightnessModeProperty().get()),
                () -> assertTrue(settings.getThemeAppearanceOverrides().contains(
                        LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE)),
                () -> assertEquals(1, transitions.get()));

        EdtDispatcher.executeAndWait(() -> store.setThemeBrightnessPreference(ThemeBrightnessPreference.THEME));

        assertAll(
                () -> assertEquals(ThemeBrightnessPreference.THEME, store.snapshot().brightnessPreference()),
                () -> assertEquals("dark", settings.themeBrightnessModeProperty().get()),
                () -> assertFalse(settings.getThemeAppearanceOverrides().contains(
                        LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE)),
                () -> assertEquals(2, transitions.get()));
        store.close();
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Animation speed is persisted directly while externally edited values are normalized for the UI model.
    @Test
    public void persistsAndNormalizesAnimationSpeed() {
        AtomicReference<@Nullable LauncherAppearanceStore> storeReference = new AtomicReference<>();
        AtomicReference<@Nullable LauncherSettings> settingsReference = new AtomicReference<>();
        AtomicInteger transitions = new AtomicInteger();

        EdtDispatcher.executeAndWait(() -> {
            LauncherSettings settings = new LauncherSettings();
            settings.animationSpeedPercentageProperty().set(217);
            LauncherAppearanceStore store = new LauncherAppearanceStore(settings, () -> true);
            store.subscribe(change -> transitions.incrementAndGet());
            storeReference.set(store);
            settingsReference.set(settings);

            assertEquals(200, store.snapshot().animationSpeed().percentage());
            store.setAnimationSpeedPercentage(140);
            settings.animationSpeedPercentageProperty().set(44);
        });

        LauncherAppearanceStore store = Objects.requireNonNull(storeReference.get());
        LauncherSettings settings = Objects.requireNonNull(settingsReference.get());
        assertAll(
                () -> assertEquals(44, settings.animationSpeedPercentageProperty().get()),
                () -> assertEquals(40, store.snapshot().animationSpeed().percentage()),
                () -> assertEquals(2, transitions.get()));
        store.close();
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// A custom theme-color replacement updates the live seed and color override in one published transition.
    @Test
    public void persistsThemeColorAppearanceAtomically() {
        AtomicReference<@Nullable LauncherAppearanceStore> storeReference = new AtomicReference<>();
        AtomicReference<@Nullable LauncherSettings> settingsReference = new AtomicReference<>();
        AtomicInteger transitions = new AtomicInteger();
        ThemeColorAppearanceSettings replacement = new ThemeColorAppearanceSettings(
                Objects.requireNonNull(ThemeColor.of("#147D64")),
                true);

        EdtDispatcher.executeAndWait(() -> {
            LauncherSettings settings = new LauncherSettings();
            settings.themeColorStyleProperty().set("future-style");
            settings.getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_COLOR_STYLE);
            LauncherAppearanceStore store = new LauncherAppearanceStore(settings, () -> true);
            store.subscribe(change -> transitions.incrementAndGet());
            settingsReference.set(settings);
            storeReference.set(store);
            store.setThemeColorAppearance(replacement);
        });

        LauncherAppearanceStore store = Objects.requireNonNull(storeReference.get());
        LauncherSettings settings = Objects.requireNonNull(settingsReference.get());
        assertAll(
                () -> assertEquals(replacement, store.snapshot().themeColor()),
                () -> assertEquals(ThemeColorType.CUSTOM, settings.themeColorTypeProperty().get()),
                () -> assertEquals(replacement.customColor(), settings.customThemeColorProperty().get()),
                () -> assertEquals("future-style", settings.themeColorStyleProperty().get()),
                () -> assertTrue(settings.getThemeAppearanceOverrides().contains(
                        LauncherSettings.THEME_APPEARANCE_COLOR)),
                () -> assertTrue(settings.getThemeAppearanceOverrides().contains(
                        LauncherSettings.THEME_APPEARANCE_COLOR_STYLE)),
                () -> assertEquals(1, transitions.get()));
        store.close();
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Unsupported historical color sources inherit the selected theme in both the editor and runtime request.
    @Test
    public void unsupportedThemeColorSourcesInheritSelectedTheme() {
        EdtDispatcher.executeAndWait(() -> {
            LauncherSettings settings = new LauncherSettings();
            settings.themeColorTypeProperty().set(ThemeColorType.SYSTEM);
            settings.getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_COLOR);
            LauncherAppearanceStore store = new LauncherAppearanceStore(settings, () -> true);

            ThemeResolutionRequest request = LauncherThemeResolutionAdapter.snapshot(
                    settings,
                    new ThemeResolveContext(ThemeBrightness.LIGHT, "windows", "zh"));

            assertFalse(store.snapshot().themeColor().overridden());
            assertNull(request.userOverrides().color());
            store.close();
        });
    }

    /// A complete background replacement updates every property and override key in one published transition.
    @Test
    public void persistsBackgroundAppearanceAtomically() {
        AtomicReference<@Nullable LauncherAppearanceStore> storeReference = new AtomicReference<>();
        AtomicReference<@Nullable LauncherSettings> settingsReference = new AtomicReference<>();
        AtomicInteger transitions = new AtomicInteger();
        BackgroundAppearanceSettings replacement = new BackgroundAppearanceSettings(
                BackgroundType.NETWORK,
                BuiltinBackground.WALLPAPER_2016_02_25.id(),
                "C:/backgrounds",
                "https://textures.example.invalid/background.png",
                "#123456",
                0.45,
                NetworkBackgroundImageCachePolicy.DISABLED,
                true,
                true,
                true);

        EdtDispatcher.executeAndWait(() -> {
            LauncherSettings settings = new LauncherSettings();
            LauncherAppearanceStore store = new LauncherAppearanceStore(settings, () -> true);
            store.subscribe(change -> transitions.incrementAndGet());
            settingsReference.set(settings);
            storeReference.set(store);
            store.setBackgroundAppearance(replacement);
        });

        LauncherAppearanceStore store = Objects.requireNonNull(storeReference.get());
        LauncherSettings settings = Objects.requireNonNull(settingsReference.get());
        assertAll(
                () -> assertEquals(replacement, store.snapshot().background()),
                () -> assertEquals(BackgroundType.NETWORK, settings.backgroundTypeProperty().get()),
                () -> assertEquals("https://textures.example.invalid/background.png",
                        settings.networkBackgroundImageUrlProperty().get()),
                () -> assertEquals(0.45, settings.backgroundOpacityProperty().get()),
                () -> assertTrue(settings.windowTransparentProperty().get()),
                () -> assertTrue(settings.getThemeAppearanceOverrides().contains(
                        LauncherSettings.THEME_APPEARANCE_BACKGROUND)),
                () -> assertTrue(settings.getThemeAppearanceOverrides().contains(
                        LauncherSettings.THEME_APPEARANCE_BACKGROUND_OPACITY)),
                () -> assertEquals(1, transitions.get()));
        store.close();
        EdtDispatcher.executeAndWait(() -> { });
    }
}
