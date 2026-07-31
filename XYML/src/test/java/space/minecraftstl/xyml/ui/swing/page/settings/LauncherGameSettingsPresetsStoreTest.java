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
import space.minecraftstl.xyml.game.GraphicsAPI;
import space.minecraftstl.xyml.game.ProcessPriority;
import space.minecraftstl.xyml.game.QuickPlayType;
import space.minecraftstl.xyml.game.Renderer;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.property.ObjectProperty;
import space.minecraftstl.xyml.observable.property.SimpleObjectProperty;
import space.minecraftstl.xyml.setting.DefaultIsolationType;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.setting.GameSettingsPresets;
import space.minecraftstl.xyml.setting.GameWindowType;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.setting.LauncherVisibility;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies complete reusable game-preset mapping between launcher properties and immutable Swing snapshots.
@NotNullByDefault
public final class LauncherGameSettingsPresetsStoreTest {
    /// Confirms every supported preset field survives one store update and snapshot round trip without loss.
    @Test
    public void updatesAndSnapshotsEveryPresetField() {
        GameSettingsPresetID id =
                GameSettingsPresetID.parse("game-settings-preset:123e4567-e89b-12d3-a456-426614174010");
        GameSettings.Preset preset = new GameSettings.Preset(id);
        GameSettingsPresets presets = new GameSettingsPresets();
        presets.getPresets().add(preset);
        ObjectProperty<@Nullable GameSettingsPresetID> defaultPreset = new SimpleObjectProperty<>(id);
        LauncherGameSettingsPresetsStore store = new LauncherGameSettingsPresetsStore(presets, defaultPreset);
        AtomicInteger snapshotChanges = new AtomicInteger();
        try (Subscription ignored = store.subscribe(change -> snapshotChanges.incrementAndGet())) {
            long initialRevision = store.snapshot().revision();
            GameSettingsPresetEditor editor = completeEditor(id);
            GameSettingsPresetsSnapshot updated = onEventDispatchThread(
                    () -> completed(store.updatePreset(editor)));

            assertAll(
                    () -> assertEquals(editor, updated.presets().get(0).editor()),
                    () -> assertEquals(initialRevision + 1L, updated.revision()),
                    () -> assertEquals(1, snapshotChanges.get()),
                    () -> assertPersistedEditor(preset, editor));

            GameSettings.Instance instance = new GameSettings.Instance();
            assertEquals(
                    LauncherVisibility.HIDE_AND_REOPEN,
                    GameSettings.resolve(preset, instance)
                            .getInheritable(GameSettings::launcherVisibilityProperty));
            instance.launcherVisibilityProperty().setValue(LauncherVisibility.CLOSE);
            instance.getOverrideProperties().add(GameSettings.PROPERTY_LAUNCHER_VISIBILITY);
            assertEquals(
                    LauncherVisibility.CLOSE,
                    GameSettings.resolve(preset, instance)
                            .getInheritable(GameSettings::launcherVisibilityProperty));
        } finally {
            store.close();
        }
    }

    /// Confirms historical null, zero, and negative numeric values can be snapshotted without normalization loss.
    @Test
    public void snapshotsHistoricalNumericSentinels() {
        GameSettings.Preset zeroMaximum = historicalPreset(
                "game-settings-preset:123e4567-e89b-12d3-a456-426614174011",
                0);
        GameSettings.Preset absentMaximum = historicalPreset(
                "game-settings-preset:123e4567-e89b-12d3-a456-426614174012",
                null);
        GameSettingsPresets presets = new GameSettingsPresets();
        presets.getPresets().add(zeroMaximum);
        presets.getPresets().add(absentMaximum);
        ObjectProperty<@Nullable GameSettingsPresetID> defaultPreset =
                new SimpleObjectProperty<>(zeroMaximum.idProperty().getValue());

        LauncherGameSettingsPresetsStore store = new LauncherGameSettingsPresetsStore(presets, defaultPreset);
        try {
            GameSettingsPresetEditor zeroEditor = store.snapshot().presets().get(0).editor();
            GameSettingsPresetEditor absentEditor = store.snapshot().presets().get(1).editor();
            assertAll(
                    () -> assertEquals(0, zeroEditor.memory().maximumMiB()),
                    () -> assertEquals(null, absentEditor.memory().maximumMiB()),
                    () -> assertEquals(-640.0D, zeroEditor.window().width()),
                    () -> assertEquals(-360.0D, zeroEditor.window().height()),
                    () -> assertEquals(-256, zeroEditor.jvm().minimumMemoryMiB()),
                    () -> assertEquals(-640.0D, absentEditor.window().width()),
                    () -> assertEquals(-360.0D, absentEditor.window().height()),
                    () -> assertEquals(-256, absentEditor.jvm().minimumMemoryMiB()));
        } finally {
            store.close();
        }
    }

    /// Confirms a newly created preset retains the core launcher's default hide behavior.
    @Test
    public void newPresetUsesCoreLauncherVisibilityDefault() {
        GameSettingsPresets presets = new GameSettingsPresets();
        ObjectProperty<@Nullable GameSettingsPresetID> defaultPreset = new SimpleObjectProperty<>();
        LauncherGameSettingsPresetsStore store = new LauncherGameSettingsPresetsStore(presets, defaultPreset);
        try {
            GameSettingsPresetsSnapshot created = onEventDispatchThread(
                    () -> completed(store.createPreset("Fresh")));

            assertEquals(
                    LauncherVisibility.HIDE,
                    created.presets().get(0).editor().launcher().visibility());
            assertEquals(
                    LauncherVisibility.HIDE,
                    presets.getPresets().get(0).launcherVisibilityProperty().getValue());
        } finally {
            store.close();
        }
    }

    /// Creates a non-default editor value spanning every reusable game-settings group.
    ///
    /// @param id target preset identity
    /// @return complete deterministic editor value
    private static GameSettingsPresetEditor completeEditor(GameSettingsPresetID id) {
        return new GameSettingsPresetEditor(
                Objects.requireNonNull(id, "id"),
                new GameSettingsPresetEditor.MemorySettings(false, 12288),
                new GameSettingsPresetEditor.JavaRuntimeSettings(
                        JavaVersionType.DETECTED,
                        "23",
                        "C:/Java/23/bin/java.exe",
                        new GameSettings.DetectedJava("23.0.1", "detected-java-hash")),
                new GameSettingsPresetEditor.WindowSettings(GameWindowType.MAXIMIZED, 1600.5D, 900.25D),
                new GameSettingsPresetEditor.LauncherSettings(
                        LauncherVisibility.HIDE_AND_REOPEN,
                        false,
                        true,
                        true,
                        true,
                        true),
                new GameSettingsPresetEditor.QuickPlaySettings(
                        QuickPlayType.REALMS,
                        "play.example.org:25565",
                        "Test World",
                        "realm-42"),
                new GameSettingsPresetEditor.LaunchOptionsSettings(
                        "C:/Games/XYML",
                        "--demo --username Tester",
                        "XYML_TEST=1\nSECOND=value",
                        ProcessPriority.HIGH),
                new GameSettingsPresetEditor.JvmSettings(
                        true,
                        true,
                        true,
                        "-XX:+UseZGC",
                        512,
                        "256"),
                new GameSettingsPresetEditor.CommandSettings(
                        "echo pre",
                        "wrapper --flag",
                        "echo post"),
                new GameSettingsPresetEditor.GraphicsSettings(
                        GraphicsAPI.VULKAN,
                        Renderer.OpenGL.ZINK,
                        Renderer.Vulkan.LAVAPIPE),
                new GameSettingsPresetEditor.NativeLibrarySettings(
                        true,
                        "C:/Games/XYML/natives",
                        true,
                        true,
                        true),
                DefaultIsolationType.NEVER);
    }

    /// Creates one raw launcher preset containing historical numeric sentinel values.
    ///
    /// @param idText serialized preset identity
    /// @param maximumMemory nullable or non-positive historical maximum heap value
    /// @return mutable preset containing raw legacy values
    private static GameSettings.Preset historicalPreset(String idText, @Nullable Integer maximumMemory) {
        GameSettings.Preset preset = new GameSettings.Preset(GameSettingsPresetID.parse(
                Objects.requireNonNull(idText, "idText")));
        preset.maxMemoryProperty().setValue(maximumMemory);
        preset.widthProperty().setValue(-640.0D);
        preset.heightProperty().setValue(-360.0D);
        preset.minMemoryProperty().setValue(-256);
        return preset;
    }

    /// Asserts every editor value was written to its corresponding mutable launcher property.
    ///
    /// @param preset persisted launcher preset
    /// @param editor expected complete editor value
    private static void assertPersistedEditor(GameSettings.Preset preset, GameSettingsPresetEditor editor) {
        GameSettings.Preset actual = Objects.requireNonNull(preset, "preset");
        GameSettingsPresetEditor expected = Objects.requireNonNull(editor, "editor");
        assertAll(
                () -> assertEquals(expected.memory().automatic(), actual.autoMemoryProperty().getValue()),
                () -> assertEquals(expected.memory().maximumMiB(), actual.maxMemoryProperty().getValue()),
                () -> assertEquals(expected.javaRuntime().type(), actual.javaTypeProperty().getValue()),
                () -> assertEquals(
                        expected.javaRuntime().customVersion(),
                        actual.customJavaVersionProperty().getValue()),
                () -> assertEquals(expected.javaRuntime().customPath(), actual.customJavaPathProperty().getValue()),
                () -> assertEquals(expected.javaRuntime().detectedJava(), actual.detectedJavaProperty().getValue()),
                () -> assertEquals(expected.window().type(), actual.windowTypeProperty().getValue()),
                () -> assertEquals(expected.window().width(), actual.widthProperty().getValue()),
                () -> assertEquals(expected.window().height(), actual.heightProperty().getValue()),
                () -> assertEquals(
                        expected.launcher().visibility(),
                        actual.launcherVisibilityProperty().getValue()),
                () -> assertEquals(expected.launcher().allowAutoAgent(), actual.allowAutoAgentProperty().getValue()),
                () -> assertEquals(
                        expected.launcher().disableAutoGameOptions(),
                        actual.disableAutoGameOptionsProperty().getValue()),
                () -> assertEquals(expected.launcher().showLogs(), actual.showLogsProperty().getValue()),
                () -> assertEquals(
                        expected.launcher().debugLog(),
                        actual.enableDebugLogOutputProperty().getValue()),
                () -> assertEquals(expected.launcher().notCheckGame(), actual.notCheckGameProperty().getValue()),
                () -> assertEquals(expected.quickPlay().type(), actual.quickPlayProperty().getValue()),
                () -> assertEquals(
                        expected.quickPlay().multiplayer(),
                        actual.quickPlayMultiplayerProperty().getValue()),
                () -> assertEquals(
                        expected.quickPlay().singleplayer(),
                        actual.quickPlaySingleplayerProperty().getValue()),
                () -> assertEquals(expected.quickPlay().realms(), actual.quickPlayRealmsProperty().getValue()),
                () -> assertEquals(
                        expected.launchOptions().runningDirectory(),
                        actual.runningDirectoryProperty().getValue()),
                () -> assertEquals(
                        expected.launchOptions().gameArguments(),
                        actual.gameArgumentsProperty().getValue()),
                () -> assertEquals(
                        expected.launchOptions().environmentVariables(),
                        actual.environmentVariablesProperty().getValue()),
                () -> assertEquals(expected.launchOptions().priority(), actual.processPriorityProperty().getValue()),
                () -> assertEquals(expected.jvm().noOptions(), actual.noJVMOptionsProperty().getValue()),
                () -> assertEquals(
                        expected.jvm().noOptimizingOptions(),
                        actual.noOptimizingJVMOptionsProperty().getValue()),
                () -> assertEquals(expected.jvm().notCheckJvm(), actual.notCheckJVMProperty().getValue()),
                () -> assertEquals(expected.jvm().options(), actual.jvmOptionsProperty().getValue()),
                () -> assertEquals(expected.jvm().minimumMemoryMiB(), actual.minMemoryProperty().getValue()),
                () -> assertEquals(
                        expected.jvm().permanentGenerationMiB(),
                        actual.permSizeProperty().getValue()),
                () -> assertEquals(expected.commands().preLaunch(), actual.preLaunchCommandProperty().getValue()),
                () -> assertEquals(expected.commands().wrapper(), actual.commandWrapperProperty().getValue()),
                () -> assertEquals(expected.commands().postExit(), actual.postExitCommandProperty().getValue()),
                () -> assertEquals(expected.graphics().backend(), actual.graphicsBackendProperty().getValue()),
                () -> assertEquals(
                        expected.graphics().openGlRenderer(),
                        actual.openGLRendererProperty().getValue()),
                () -> assertEquals(
                        expected.graphics().vulkanRenderer(),
                        actual.vulkanRendererProperty().getValue()),
                () -> assertEquals(
                        expected.nativeLibraries().customDirectoryEnabled(),
                        actual.useCustomNativesProperty().getValue()),
                () -> assertEquals(
                        expected.nativeLibraries().directory(),
                        actual.nativesDirectoryProperty().getValue()),
                () -> assertEquals(
                        expected.nativeLibraries().patchingDisabled(),
                        actual.notPatchNativesProperty().getValue()),
                () -> assertEquals(
                        expected.nativeLibraries().nativeGlfw(),
                        actual.useNativeGLFWProperty().getValue()),
                () -> assertEquals(
                        expected.nativeLibraries().nativeOpenAl(),
                        actual.useNativeOpenALProperty().getValue()),
                () -> assertEquals(expected.defaultIsolationType(), actual.defaultIsolationTypeProperty().getValue()));
    }

    /// Returns the successful value of an already completed in-memory store command.
    ///
    /// @param stage store command completion
    /// @param <T> completion value type
    /// @return successful command value
    private static <T> T completed(CompletionStage<T> stage) {
        return Objects.requireNonNull(stage, "stage").toCompletableFuture().join();
    }

    /// Runs a supplier on the Swing EDT and returns its non-null result.
    ///
    /// @param supplier EDT-bound supplier
    /// @param <T> result type
    /// @return supplier result
    private static <T> T onEventDispatchThread(Supplier<T> supplier) {
        AtomicReference<T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(Objects.requireNonNull(supplier, "supplier").get()));
        return Objects.requireNonNull(result.get(), "EDT supplier result");
    }
}
