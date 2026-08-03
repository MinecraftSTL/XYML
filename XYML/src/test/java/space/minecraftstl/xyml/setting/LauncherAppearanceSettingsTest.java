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
package space.minecraftstl.xyml.setting;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Objects;

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

        assertEquals(LauncherSettings.DEFAULT_CORNER_RADIUS, settings.cornerRadiusProperty().get());
        assertEquals(
                LauncherSettings.DEFAULT_ANIMATION_SPEED_PERCENTAGE,
                settings.animationSpeedPercentageProperty().get());
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

    /// Animation-speed ranges reject a maximum that cannot be represented by the configured step.
    @Test
    public void validatesAnimationSpeedSliderGrid() {
        assertThrows(IllegalArgumentException.class, () -> new AnimationSpeedSettings(100, 50, 195, 10));
        assertFalse(AnimationSpeedSettings.defaults().isInstant());
        assertTrue(new AnimationSpeedSettings(200, 50, 200, 10).isInstant());
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
