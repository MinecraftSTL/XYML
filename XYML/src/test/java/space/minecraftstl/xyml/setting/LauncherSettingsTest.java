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
import space.minecraftstl.xyml.upgrade.UpdateChannel;
import space.minecraftstl.xyml.util.gson.JsonSchema;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests current launcher settings serialization behavior.
@NotNullByDefault
public final class LauncherSettingsTest {
    /// Persists an explicit update source while leaving the obsolete preview preference unmigrated.
    @Test
    public void updateChannelDefaultsAndPersistsIndependently() {
        LauncherSettings defaults = new LauncherSettings();
        assertEquals(UpdateChannel.getChannel(), defaults.getEffectiveUpdateChannel());

        LauncherSettings configured = LauncherSettings.fromJson(JsonParser.parseString("""
                {
                  "updateChannel": "ALPHA",
                  "acceptPreviewUpdate": true
                }
                """).getAsJsonObject());
        assertEquals(UpdateChannel.ALPHA, configured.updateChannelProperty().get());

        LauncherSettings legacy = LauncherSettings.fromJson(
                JsonParser.parseString("{\"acceptPreviewUpdate\":true}").getAsJsonObject());
        assertEquals(UpdateChannel.getChannel(), legacy.getEffectiveUpdateChannel());
        assertTrue(JsonParser.parseString(legacy.toJson()).getAsJsonObject()
                .get("acceptPreviewUpdate").getAsBoolean());

        LauncherSettings invalid = LauncherSettings.fromJson(
                JsonParser.parseString("{\"updateChannel\":\"NIGHTLY\"}").getAsJsonObject());
        assertEquals(UpdateChannel.getChannel(), invalid.getEffectiveUpdateChannel());

        JsonObject serialized = JsonParser.parseString(configured.toJson()).getAsJsonObject();
        assertEquals("ALPHA", serialized.get("updateChannel").getAsString());
    }

    /// Verifies the MCP listener and independent deletion-confirmation defaults and persistence.
    @Test
    public void mcpServerDefaultsToDisabled() {
        LauncherSettings settings = new LauncherSettings();

        assertFalse(settings.mcpEnabledProperty().get());
        assertEquals(LauncherSettings.DEFAULT_MCP_BEARER_TOKEN, settings.mcpBearerTokenProperty().get());
        assertTrue(settings.showMcpEnablementWarningProperty().get());
        assertEquals(LauncherSettings.DEFAULT_MCP_PORT, settings.mcpPortProperty().get());
        assertTrue(settings.mcpConfirmInstanceDeletionProperty().get());
        assertTrue(settings.mcpConfirmModDeletionProperty().get());
        settings.mcpEnabledProperty().set(true);
        settings.mcpBearerTokenProperty().set("test-token");
        settings.showMcpEnablementWarningProperty().set(false);
        settings.mcpPortProperty().set(23969);
        settings.mcpConfirmInstanceDeletionProperty().set(false);
        settings.mcpConfirmModDeletionProperty().set(false);
        JsonObject serialized = JsonParser.parseString(settings.toJson()).getAsJsonObject();
        assertTrue(serialized.get("mcpEnabled").getAsBoolean());
        assertEquals("test-token", serialized.get("mcpBearerToken").getAsString());
        assertFalse(serialized.get("showMcpEnablementWarning").getAsBoolean());
        assertEquals(23969, serialized.get("mcpPort").getAsInt());
        assertFalse(serialized.get("mcpConfirmInstanceDeletion").getAsBoolean());
        assertFalse(serialized.get("mcpConfirmModDeletion").getAsBoolean());
        assertFalse(serialized.has("mcpConfirmDeletion"));

        LauncherSettings migrated = LauncherSettings.fromJson(JsonParser.parseString("{\"mcpPort\":23969}")
                .getAsJsonObject());
        assertEquals(23969, migrated.mcpPortProperty().get());
        assertEquals(LauncherSettings.DEFAULT_MCP_BEARER_TOKEN, migrated.mcpBearerTokenProperty().get());
        assertTrue(migrated.showMcpEnablementWarningProperty().get());
        assertTrue(migrated.mcpConfirmInstanceDeletionProperty().get());
        assertTrue(migrated.mcpConfirmModDeletionProperty().get());
        JsonObject migratedJson = JsonParser.parseString(migrated.toJson()).getAsJsonObject();
        assertFalse(migratedJson.has("mcpEnabled"));
        assertFalse(migratedJson.has("mcpBearerToken"));
        assertFalse(migratedJson.has("showMcpEnablementWarning"));

        LauncherSettings confirmationDisabled = LauncherSettings.fromJson(
                JsonParser.parseString("{\"mcpConfirmDeletion\":false}").getAsJsonObject());
        assertFalse(confirmationDisabled.mcpConfirmInstanceDeletionProperty().get());
        assertFalse(confirmationDisabled.mcpConfirmModDeletionProperty().get());

        LauncherSettings partiallyMigrated = LauncherSettings.fromJson(JsonParser.parseString("""
                {
                  "mcpConfirmDeletion": false,
                  "mcpConfirmInstanceDeletion": true
                }
                """).getAsJsonObject());
        assertTrue(partiallyMigrated.mcpConfirmInstanceDeletionProperty().get());
        assertFalse(partiallyMigrated.mcpConfirmModDeletionProperty().get());
        assertFalse(JsonParser.parseString(partiallyMigrated.toJson()).getAsJsonObject()
                .has("mcpConfirmDeletion"));

        LauncherSettings invalid = LauncherSettings.fromJson(JsonParser.parseString("{\"mcpPort\":70000}")
                .getAsJsonObject());
        assertEquals(LauncherSettings.DEFAULT_MCP_PORT, invalid.mcpPortProperty().get());
    }

    /// Preserves explicit MCP authentication and warning preferences during deserialization.
    @Test
    public void preservesExplicitMcpSecurityPreferences() {
        LauncherSettings settings = LauncherSettings.fromJson(JsonParser.parseString("""
                {
                  "mcpEnabled": true,
                  "mcpBearerToken": "configured-token",
                  "showMcpEnablementWarning": false
                }
                """).getAsJsonObject());

        assertTrue(settings.mcpEnabledProperty().get());
        assertEquals("configured-token", settings.mcpBearerTokenProperty().get());
        assertFalse(settings.showMcpEnablementWarningProperty().get());
    }

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
