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
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.util.gson.JsonSchema;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests current launcher settings serialization behavior.
@NotNullByDefault
public final class LauncherSettingsTest {
    /// Tests that launcher settings serialization preserves a patch-version schema and unknown fields.
    @Test
    public void preservesPatchSchemaAndUnknownFields() {
        LauncherSettings launcherSettings = Objects.requireNonNull(LauncherSettings.fromJson(JsonParser.parseString("""
                {
                  "$schema": "https://raw.githubusercontent.com/MinecraftSTL/XYML/main/docs/schemas/launcher-settings/1.0.1.json",
                  "futureField": true
                }
                """).getAsJsonObject()));

        JsonObject serialized = JsonParser.parseString(launcherSettings.toJson()).getAsJsonObject();

        assertEquals("https://raw.githubusercontent.com/MinecraftSTL/XYML/main/docs/schemas/launcher-settings/1.0.1.json",
                serialized.get(JsonSchema.PROPERTY_SCHEMA).getAsString());
        assertTrue(serialized.get("futureField").getAsBoolean());
    }
}
