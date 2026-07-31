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
import space.minecraftstl.xyml.game.Renderer;
import space.minecraftstl.xyml.observable.collection.ObservableSet;
import space.minecraftstl.xyml.observable.property.ObjectProperty;
import space.minecraftstl.xyml.util.gson.JsonSchema;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for instance-specific game settings.
@NotNullByDefault
public final class GameSettingsInstanceTest {
    /// Tests that persistent fields expose toolkit-neutral types and publish aggregate revisions.
    @Test
    public void publishesToolkitNeutralPropertyAndOverrideChanges() {
        GameSettings.Instance instance = new GameSettings.Instance();
        ObjectProperty<JsonSchema> schema = instance.schemaProperty();
        ObservableSet<String> overrides = instance.getOverrideProperties();
        long initialRevision = Objects.requireNonNull(instance.changes().getValue());

        instance.jvmOptionsProperty().setValue("-XX:+UseG1GC");
        long afterProperty = Objects.requireNonNull(instance.changes().getValue());
        overrides.add(GameSettings.PROPERTY_JVM_OPTIONS);

        assertEquals(GameSettings.Instance.CURRENT_SCHEMA, schema.get());
        assertTrue(afterProperty > initialRevision);
        assertTrue(Objects.requireNonNull(instance.changes().getValue()) > afterProperty);
    }

    /// Tests that directory settings are serialized with full `Directory` property names.
    @Test
    public void storesDirectoryPropertyNames() {
        GameSettings.Instance instance = new GameSettings.Instance();
        instance.runningDirectoryProperty().setValue("run");
        instance.useCustomNativesProperty().setValue(true);
        instance.nativesDirectoryProperty().setValue("natives");

        JsonObject serialized = JsonParser.parseString(
                LauncherSettings.SETTINGS_GSON.toJson(instance, GameSettings.Instance.class)
        ).getAsJsonObject();

        assertEquals("run", serialized.get("runningDirectory").getAsString());
        assertTrue(serialized.get("useCustomNatives").getAsBoolean());
        assertEquals("natives", serialized.get("nativesDirectory").getAsString());
        assertFalse(serialized.has("runningDir"));
        assertFalse(serialized.has("nativesDirectoryType"));
        assertFalse(serialized.has("nativesDirType"));
        assertFalse(serialized.has("nativesDir"));
    }

    /// Tests that renderer settings are stored as renderer names and restored from them.
    @Test
    public void roundTripsRenderers() {
        GameSettings.Instance instance = new GameSettings.Instance();
        instance.openGLRendererProperty().setValue(Renderer.OpenGL.LLVMPIPE);
        instance.vulkanRendererProperty().setValue(Renderer.Vulkan.LAVAPIPE);

        String serialized = LauncherSettings.SETTINGS_GSON.toJson(instance, GameSettings.Instance.class);
        JsonObject jsonObject = JsonParser.parseString(serialized).getAsJsonObject();
        assertEquals("LLVMPIPE", jsonObject.get(GameSettings.PROPERTY_OPENGL_RENDERER).getAsString());
        assertEquals("LAVAPIPE", jsonObject.get(GameSettings.PROPERTY_VULKAN_RENDERER).getAsString());

        GameSettings.Instance deserialized =
                LauncherSettings.SETTINGS_GSON.fromJson(serialized, GameSettings.Instance.class);

        assertEquals(Renderer.OpenGL.LLVMPIPE, deserialized.openGLRendererProperty().getValue());
        assertEquals(Renderer.Vulkan.LAVAPIPE, deserialized.vulkanRendererProperty().getValue());
    }

    /// Tests that Java payload settings inherit independently from the Java selection mode.
    @Test
    public void inheritsJavaPayloadPropertiesIndependently() {
        GameSettings.Preset parent = new GameSettings.Preset(
                GameSettingsPresetID.parse("game-settings-preset:123e4567-e89b-12d3-a456-426614174000"));
        parent.javaTypeProperty().setValue(JavaVersionType.VERSION);
        parent.customJavaVersionProperty().setValue("17");
        parent.customJavaPathProperty().setValue("/parent/java");
        parent.detectedJavaProperty().setValue(new GameSettings.DetectedJava("17.0.11+9", "parent-hash"));

        GameSettings.Instance instance = new GameSettings.Instance();
        instance.javaTypeProperty().setValue(JavaVersionType.CUSTOM);
        instance.customJavaVersionProperty().setValue("21");
        instance.customJavaPathProperty().setValue("/instance/java");
        instance.detectedJavaProperty().setValue(new GameSettings.DetectedJava("21.0.1+12", "instance-hash"));
        instance.getOverrideProperties().add(GameSettings.PROPERTY_JAVA_TYPE);
        instance.getOverrideProperties().add(GameSettings.PROPERTY_CUSTOM_JAVA_PATH);

        GameSettings.Effective effective = GameSettings.resolve(parent, instance);

        assertEquals(JavaVersionType.CUSTOM, effective.getInheritable(GameSettings::javaTypeProperty));
        assertEquals("17", effective.getInheritable(GameSettings::customJavaVersionProperty));
        assertEquals("/instance/java", effective.getInheritable(GameSettings::customJavaPathProperty));
        assertEquals("17.0.11+9", effective.getInheritable(GameSettings::detectedJavaProperty).version());
    }

}
