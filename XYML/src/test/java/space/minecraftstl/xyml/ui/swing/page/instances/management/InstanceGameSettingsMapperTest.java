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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.GraphicsAPI;
import space.minecraftstl.xyml.game.ProcessPriority;
import space.minecraftstl.xyml.game.QuickPlayType;
import space.minecraftstl.xyml.game.Renderer;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.setting.GameWindowType;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.setting.LauncherVisibility;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies complete instance-setting mapping preserves every serialized inheritance marker independently.
@NotNullByDefault
final class InstanceGameSettingsMapperTest {
    /// Writes and reads every supported setting without losing values or override keys.
    @Test
    void roundTripsAllLocalSettings() {
        GameSettings.Preset preset = new GameSettings.Preset(GameSettingsPresetID.generate());
        GameSettings.Instance instance = new GameSettings.Instance();
        InstanceGameSettingsSnapshot expected = snapshot(true);

        InstanceGameSettingsMapper.apply(instance, expected);

        assertEquals(expected, InstanceGameSettingsMapper.snapshot(
                true,
                expected.parentPreset(),
                instance,
                GameSettings.resolve(preset, instance)));
        assertEquals(allOverrideKeys(), instance.getOverrideProperties());
    }

    /// Returning every property to inheritance removes only markers and retains dormant local values.
    @Test
    void clearsOverridesWithoutDiscardingDormantValues() {
        GameSettings.Preset preset = new GameSettings.Preset(GameSettingsPresetID.generate());
        GameSettings.Instance instance = new GameSettings.Instance();
        InstanceGameSettingsMapper.apply(instance, snapshot(true));

        InstanceGameSettingsMapper.apply(instance, snapshot(false));

        assertTrue(instance.getOverrideProperties().isEmpty());
        assertEquals(false, instance.autoMemoryProperty().getValue());
        assertEquals(8192, instance.maxMemoryProperty().getValue());
        assertEquals(JavaVersionType.DETECTED, instance.javaTypeProperty().getValue());
        assertEquals(GameWindowType.MAXIMIZED, instance.windowTypeProperty().getValue());
        assertEquals("--demo", instance.gameArgumentsProperty().getValue());
        assertEquals("echo ready", instance.preLaunchCommandProperty().getValue());
        assertEquals(GraphicsAPI.VULKAN, instance.graphicsBackendProperty().getValue());
        assertEquals("native-bin", instance.nativesDirectoryProperty().getValue());
        InstanceGameSettingsSnapshot inherited = InstanceGameSettingsMapper.snapshot(
                true,
                new InstanceGameSettingsSnapshot.ParentPresetSettings(null, List.of()),
                instance,
                GameSettings.resolve(preset, instance));
        assertFalse(inherited.memory().anyOverridden());
        assertTrue(inherited.memory().automatic());
        assertFalse(inherited.javaRuntime().anyOverridden());
        assertEquals(JavaVersionType.AUTO, inherited.javaRuntime().type());
        assertFalse(inherited.window().typeOverridden());
        assertEquals(GameWindowType.WINDOWED, inherited.window().type());
        assertFalse(inherited.graphics().backendOverridden());
        assertEquals(GraphicsAPI.DEFAULT, inherited.graphics().backend());
    }

    /// Validates an empty detected-runtime reference only when the instance locally selects detected Java.
    @Test
    void validatesOnlyLocalDetectedJavaSelection() {
        GameSettings.Instance instance = new GameSettings.Instance();
        InstanceGameSettingsSnapshot base = snapshot(false);
        InstanceGameSettingsSnapshot inheritedDetected = withJavaRuntime(
                base,
                new InstanceGameSettingsSnapshot.JavaRuntimeSettings(
                        false,
                        JavaVersionType.DETECTED,
                        false,
                        "",
                        false,
                        "",
                        false,
                        GameSettings.DetectedJava.EMPTY));
        InstanceGameSettingsSnapshot localDetected = withJavaRuntime(
                base,
                new InstanceGameSettingsSnapshot.JavaRuntimeSettings(
                        true,
                        JavaVersionType.DETECTED,
                        false,
                        "",
                        false,
                        "",
                        false,
                        GameSettings.DetectedJava.EMPTY));

        assertDoesNotThrow(() -> InstanceGameSettingsMapper.apply(instance, inheritedDetected));
        assertThrows(IllegalArgumentException.class, () -> InstanceGameSettingsMapper.apply(instance, localDetected));
    }

    /// Persists the selected global parent preset independently from inheritable override markers.
    @Test
    void persistsParentPresetSelection() {
        GameSettingsPresetID parentId = GameSettingsPresetID.generate();
        InstanceGameSettingsSnapshot source = snapshot(false);
        InstanceGameSettingsSnapshot selected = new InstanceGameSettingsSnapshot(
                source.writable(),
                new InstanceGameSettingsSnapshot.ParentPresetSettings(parentId, List.of()),
                source.memory(),
                source.javaRuntime(),
                source.window(),
                source.launcher(),
                source.quickPlay(),
                source.launchOptions(),
                source.jvm(),
                source.commands(),
                source.graphics(),
                source.nativeLibraries());
        GameSettings.Instance instance = new GameSettings.Instance();

        InstanceGameSettingsMapper.apply(instance, selected);

        assertEquals(parentId, instance.parentProperty().getValue());
        assertTrue(instance.getOverrideProperties().isEmpty());
    }

    /// Creates a complete snapshot whose values are stable while all override flags share one requested state.
    ///
    /// @param overridden whether every property should be local
    /// @return complete test snapshot
    private static InstanceGameSettingsSnapshot snapshot(boolean overridden) {
        return new InstanceGameSettingsSnapshot(
                true,
                new InstanceGameSettingsSnapshot.ParentPresetSettings(null, List.of()),
                new InstanceGameSettingsSnapshot.MemorySettings(overridden, false, overridden, 8192),
                new InstanceGameSettingsSnapshot.JavaRuntimeSettings(
                        overridden,
                        JavaVersionType.DETECTED,
                        overridden,
                        "21",
                        overridden,
                        "runtime/bin/java",
                        overridden,
                        new GameSettings.DetectedJava("21.0.5", "runtime-hash")),
                new InstanceGameSettingsSnapshot.WindowSettings(
                        overridden,
                        GameWindowType.MAXIMIZED,
                        overridden,
                        854.5D,
                        overridden,
                        100_000.25D),
                new InstanceGameSettingsSnapshot.LauncherSettings(
                        overridden,
                        LauncherVisibility.KEEP,
                        overridden,
                        true,
                        overridden,
                        true,
                        overridden,
                        true,
                        overridden,
                        true,
                        overridden,
                        true),
                new InstanceGameSettingsSnapshot.QuickPlaySettings(
                        overridden,
                        QuickPlayType.MULTIPLAYER,
                        overridden,
                        "localhost:25565",
                        overridden,
                        "Test World",
                        overridden,
                        "12345"),
                new InstanceGameSettingsSnapshot.LaunchOptionsSettings(
                        overridden,
                        "instance-run",
                        overridden,
                        "--demo",
                        overridden,
                        "XYML_TEST=1",
                        overridden,
                        ProcessPriority.HIGH),
                new InstanceGameSettingsSnapshot.JvmSettings(
                        overridden,
                        true,
                        overridden,
                        true,
                        overridden,
                        true,
                        overridden,
                        "-XX:+UseG1GC",
                        overridden,
                        512,
                        overridden,
                        "256"),
                new InstanceGameSettingsSnapshot.CommandSettings(
                        overridden,
                        "echo ready",
                        overridden,
                        "wrapper",
                        overridden,
                        "echo done"),
                new InstanceGameSettingsSnapshot.GraphicsSettings(
                        overridden,
                        GraphicsAPI.VULKAN,
                        overridden,
                        Renderer.OpenGL.ZINK,
                        overridden,
                        Renderer.Vulkan.LAVAPIPE),
                new InstanceGameSettingsSnapshot.NativeLibrarySettings(
                        overridden,
                        true,
                        overridden,
                        "native-bin",
                        overridden,
                        true,
                        overridden,
                        true,
                        overridden,
                        true));
    }

    /// Copies a snapshot with one replacement Java-runtime group.
    ///
    /// @param source source snapshot
    /// @param javaRuntime replacement Java-runtime settings
    /// @return copied snapshot
    private static InstanceGameSettingsSnapshot withJavaRuntime(
            InstanceGameSettingsSnapshot source,
            InstanceGameSettingsSnapshot.JavaRuntimeSettings javaRuntime) {
        return new InstanceGameSettingsSnapshot(
                source.writable(),
                source.parentPreset(),
                source.memory(),
                javaRuntime,
                source.window(),
                source.launcher(),
                source.quickPlay(),
                source.launchOptions(),
                source.jvm(),
                source.commands(),
                source.graphics(),
                source.nativeLibraries());
    }

    /// Returns every inheritable serialized property key represented by the Swing snapshot.
    ///
    /// @return immutable complete override-key set
    private static @Unmodifiable Set<String> allOverrideKeys() {
        return Set.of(
                GameSettings.PROPERTY_JAVA_TYPE,
                GameSettings.PROPERTY_CUSTOM_JAVA_VERSION,
                GameSettings.PROPERTY_CUSTOM_JAVA_PATH,
                GameSettings.PROPERTY_DETECTED_JAVA,
                GameSettings.PROPERTY_JVM_OPTIONS,
                GameSettings.PROPERTY_NO_JVM_OPTIONS,
                GameSettings.PROPERTY_NO_OPTIMIZING_JVM_OPTIONS,
                GameSettings.PROPERTY_NOT_CHECK_JVM,
                GameSettings.PROPERTY_NOT_CHECK_GAME,
                GameSettings.PROPERTY_AUTO_MEMORY,
                GameSettings.PROPERTY_MIN_MEMORY,
                GameSettings.PROPERTY_MAX_MEMORY,
                GameSettings.PROPERTY_PERM_SIZE,
                GameSettings.PROPERTY_WINDOW_TYPE,
                GameSettings.PROPERTY_WIDTH,
                GameSettings.PROPERTY_HEIGHT,
                GameSettings.PROPERTY_RUNNING_DIRECTORY,
                GameSettings.PROPERTY_PROCESS_PRIORITY,
                GameSettings.PROPERTY_LAUNCHER_VISIBILITY,
                GameSettings.PROPERTY_ALLOW_AUTO_AGENT,
                GameSettings.PROPERTY_DISABLE_AUTO_GAME_OPTIONS,
                GameSettings.PROPERTY_GAME_ARGS,
                GameSettings.PROPERTY_GRAPHICS_BACKEND,
                GameSettings.PROPERTY_OPENGL_RENDERER,
                GameSettings.PROPERTY_VULKAN_RENDERER,
                GameSettings.PROPERTY_ENVIRONMENT_VARIABLES,
                GameSettings.PROPERTY_COMMAND_WRAPPER,
                GameSettings.PROPERTY_PRE_LAUNCH_COMMAND,
                GameSettings.PROPERTY_POST_EXIT_COMMAND,
                GameSettings.PROPERTY_QUICK_PLAY,
                GameSettings.PROPERTY_QUICK_PLAY_MULTIPLAYER,
                GameSettings.PROPERTY_QUICK_PLAY_SINGLEPLAYER,
                GameSettings.PROPERTY_QUICK_PLAY_REALMS,
                GameSettings.PROPERTY_SHOW_LOGS,
                GameSettings.PROPERTY_ENABLE_DEBUG_LOG_OUTPUT,
                GameSettings.PROPERTY_NOT_PATCH_NATIVES,
                GameSettings.PROPERTY_USE_CUSTOM_NATIVES,
                GameSettings.PROPERTY_NATIVES_DIRECTORY,
                GameSettings.PROPERTY_USE_NATIVE_GLFW,
                GameSettings.PROPERTY_USE_NATIVE_OPENAL);
    }
}
