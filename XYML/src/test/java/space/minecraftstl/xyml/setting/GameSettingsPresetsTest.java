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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.gson.JsonParseException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import space.minecraftstl.xyml.util.FileSaver;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.i18n.LocalizedText;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for detached game settings presets.
@NotNullByDefault
public final class GameSettingsPresetsTest {
    /// Tests that a nested preset property update propagates through the neutral list extractor to auto-save.
    @Test
    public void autoSavesNestedPresetChanges() throws IOException, InterruptedException {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path location = fileSystem.getPath("/game-settings.json");
            JsonSettingFile<GameSettingsPresets> file = new JsonSettingFile<>(
                    location,
                    "game settings presets",
                    GameSettingsPresets.class,
                    GameSettingsPresets.CURRENT_SCHEMA,
                    GameSettingsPresets::new);
            GameSettingsPresets presets = new GameSettingsPresets();
            GameSettings.Preset preset = new GameSettings.Preset(
                    GameSettingsPresetID.parse("game-settings-preset:123e4567-e89b-12d3-a456-426614174000"));
            presets.getPresets().add(preset);
            file.installAutoSave(presets);

            preset.nameProperty().setValue(LocalizedText.plain("Nested update"));
            FileSaver.waitForAllSaves();

            JsonObject saved = Objects.requireNonNull(JsonUtils.fromJsonFile(location, JsonObject.class));
            JsonObject savedPreset = saved.getAsJsonArray("presets").get(0).getAsJsonObject();
            assertEquals("Nested update", savedPreset.get("name").getAsString());
        }
    }

    /// Tests that the default preset selection belongs to LauncherSettings.
    @Test
    public void storesDefaultGameSettingsPresetInConfig() {
        GameSettingsPresetID id =
                GameSettingsPresetID.parse("game-settings-preset:123e4567-e89b-12d3-a456-426614174000");
        LauncherSettings config = new LauncherSettings();

        config.defaultGameSettingsPresetProperty().set(id);
        JsonObject serialized = JsonParser.parseString(config.toJson()).getAsJsonObject();

        assertEquals(id.toString(), serialized.get(LauncherSettings.PROPERTY_DEFAULT_GAME_SETTINGS_PRESET).getAsString());
    }

    /// Tests that presets must be deserialized with a non-nil ID.
    @Test
    public void rejectsNilPresetId() {
        assertThrows(JsonParseException.class, () -> JsonUtils.GSON.fromJson("""
                {
                  "id": "game-settings-preset:00000000-0000-0000-0000-000000000000"
                }
                """, GameSettings.Preset.class));

        assertThrows(JsonParseException.class,
                () -> JsonUtils.GSON.fromJson("{}", GameSettings.Preset.class));
    }

    /// Tests that automatic preset name numbers are stored separately from custom names.
    @Test
    public void storesAutomaticPresetNameNumber() {
        GameSettings.Preset preset = new GameSettings.Preset(
                GameSettingsPresetID.parse("game-settings-preset:123e4567-e89b-12d3-a456-426614174000"));

        preset.autoNameNumberProperty().setValue(3);
        JsonObject serialized = JsonParser.parseString(JsonUtils.GSON.toJson(preset, GameSettings.Preset.class))
                .getAsJsonObject();

        assertFalse(serialized.has("name"));
        assertEquals(3, serialized.get("autoNameNumber").getAsInt());
    }

    /// Tests that custom preset names are stored as strings.
    @Test
    public void storesCustomPresetNameAsString() {
        GameSettings.Preset preset = new GameSettings.Preset(
                GameSettingsPresetID.parse("game-settings-preset:123e4567-e89b-12d3-a456-426614174000"));

        preset.nameProperty().setValue(LocalizedText.plain("Custom"));
        JsonObject serialized = JsonParser.parseString(JsonUtils.GSON.toJson(preset, GameSettings.Preset.class))
                .getAsJsonObject();

        assertEquals("Custom", serialized.get("name").getAsString());
        assertFalse(serialized.has("autoNameNumber"));
    }

    /// Tests that custom preset names and automatic name numbers are read independently.
    @Test
    public void readsCustomNameAndAutomaticNameNumber() {
        GameSettings.Preset automatic = JsonUtils.GSON.fromJson("""
                {
                  "id": "game-settings-preset:123e4567-e89b-12d3-a456-426614174000",
                  "autoNameNumber": 4
                }
                """, GameSettings.Preset.class);
        GameSettings.Preset custom = JsonUtils.GSON.fromJson("""
                {
                  "id": "game-settings-preset:123e4567-e89b-12d3-a456-426614174001",
                  "name": "Custom"
                }
                """, GameSettings.Preset.class);

        assertEquals(4, automatic.autoNameNumberProperty().getValue());
        assertNull(automatic.nameProperty().getValue());
        assertNull(custom.autoNameNumberProperty().getValue());
        assertEquals("Custom", Objects.requireNonNull(custom.nameProperty().getValue()).getText(List.of(Locale.ENGLISH)));
    }

}
