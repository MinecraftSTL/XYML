// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.setting;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests persistence and defaults for Swing appearance settings.
@NotNullByDefault
public final class LauncherAppearanceSettingsTest {
    /// A new settings object uses the documented appearance defaults.
    @Test
    public void usesDefaultAppearanceValues() {
        LauncherSettings settings = new LauncherSettings();

        assertAll(
                () -> assertEquals(
                        LauncherSettings.DEFAULT_CORNER_RADIUS,
                        settings.cornerRadiusProperty().get()),
                () -> assertEquals(20, LauncherSettings.MAXIMUM_CORNER_RADIUS),
                () -> assertEquals(
                        LauncherSettings.DEFAULT_ANIMATION_SPEED_PERCENTAGE,
                        settings.animationSpeedPercentageProperty().get()));
    }

    /// Changed appearance values survive launcher-settings JSON serialization and deserialization.
    @Test
    public void persistsChangedAppearanceValues() {
        LauncherSettings settings = new LauncherSettings();
        settings.cornerRadiusProperty().set(14);
        settings.animationSpeedPercentageProperty().set(170);

        JsonObject serialized = LauncherSettings.SETTINGS_GSON.toJsonTree(settings).getAsJsonObject();
        LauncherSettings restored = Objects.requireNonNull(LauncherSettings.fromJson(serialized));

        assertEquals(14, serialized.get("cornerRadius").getAsInt());
        assertEquals(14, restored.cornerRadiusProperty().get());
        assertEquals(170, serialized.get("animationSpeedPercentage").getAsInt());
        assertEquals(170, restored.animationSpeedPercentageProperty().get());
    }

    /// Animation speed exposes exactly the requested finite bands followed by an instant endpoint.
    @Test
    public void exposesDiscreteAnimationSpeedSliderScale() {
        assertEquals(
                java.util.List.of(
                        10, 20, 30, 40, 50, 60, 70, 80, 90, 100,
                        120, 140, 160, 180, 200,
                        250, 300, 350, 400, 450, 500,
                        AnimationSpeedSettings.INSTANT_PERCENTAGE),
                IntStream.range(0, AnimationSpeedSettings.sliderPositionCount())
                        .mapToObj(AnimationSpeedSettings::percentageAtSliderPosition)
                        .toList());
        assertThrows(IllegalArgumentException.class, () -> new AnimationSpeedSettings(110));
        assertFalse(AnimationSpeedSettings.defaults().isInstant());
        assertTrue(new AnimationSpeedSettings(AnimationSpeedSettings.INSTANT_PERCENTAGE).isInstant());
    }

    /// Externally edited values select the nearest finite notch without inventing an instant setting.
    @Test
    public void normalizesExternalAnimationSpeedValues() {
        assertEquals(10, AnimationSpeedSettings.normalizePercentage(-100));
        assertEquals(120, AnimationSpeedSettings.normalizePercentage(130));
        assertEquals(500, AnimationSpeedSettings.normalizePercentage(900));
        assertEquals(
                AnimationSpeedSettings.INSTANT_PERCENTAGE,
                AnimationSpeedSettings.normalizePercentage(AnimationSpeedSettings.INSTANT_PERCENTAGE));
    }

    /// Window transparency persists directly while retired background settings are discarded.
    @Test
    public void persistsWindowTransparencyWithoutFallbackSettings() {
        LauncherSettings settings = new LauncherSettings();
        settings.windowTransparentProperty().set(true);
        settings.getThemeAppearanceOverrides().add("windowTransparent");

        JsonObject serialized = LauncherSettings.SETTINGS_GSON.toJsonTree(settings).getAsJsonObject();
        assertFalse(serialized.has("backgroundFallbackType"));
        assertFalse(serialized.has("backgroundFallbackPaint"));
        assertFalse(serialized.has("backgroundLoadPolicy"));
        serialized.addProperty("backgroundFallbackType", "PAINT");
        serialized.addProperty("backgroundFallbackPaint", "#123456");
        serialized.addProperty("backgroundLoadPolicy", "SHOW_FALLBACK_WHILE_LOADING");

        LauncherSettings restored = Objects.requireNonNull(LauncherSettings.fromJson(serialized));
        JsonObject normalized = LauncherSettings.SETTINGS_GSON.toJsonTree(restored).getAsJsonObject();

        assertTrue(serialized.get("windowTransparent").getAsBoolean());
        assertTrue(restored.windowTransparentProperty().get());
        assertFalse(restored.getThemeAppearanceOverrides().contains("windowTransparent"));
        assertFalse(normalized.getAsJsonArray("themeAppearanceOverrides")
                .contains(new JsonPrimitive("windowTransparent")));
        assertFalse(normalized.has("backgroundFallbackType"));
        assertFalse(normalized.has("backgroundFallbackPaint"));
        assertFalse(normalized.has("backgroundLoadPolicy"));
    }

    /// Loading settings retires color-source overrides that the Swing runtime cannot render faithfully.
    @Test
    public void removesUnsupportedThemeColorSourceOverrides() {
        for (ThemeColorType type : ThemeColorType.values()) {
            LauncherSettings settings = new LauncherSettings();
            settings.themeColorTypeProperty().set(type);
            settings.getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_COLOR);

            JsonObject serialized = LauncherSettings.SETTINGS_GSON.toJsonTree(settings).getAsJsonObject();
            LauncherSettings restored = Objects.requireNonNull(LauncherSettings.fromJson(serialized));

            assertEquals(
                    type == ThemeColorType.CUSTOM,
                    restored.getThemeAppearanceOverrides().contains(LauncherSettings.THEME_APPEARANCE_COLOR),
                    type.name());
        }
    }
}
