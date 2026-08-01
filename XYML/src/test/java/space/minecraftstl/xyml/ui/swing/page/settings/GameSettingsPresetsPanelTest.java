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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.GraphicsAPI;
import space.minecraftstl.xyml.game.ProcessPriority;
import space.minecraftstl.xyml.game.QuickPlayType;
import space.minecraftstl.xyml.game.Renderer;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.setting.DefaultIsolationType;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.setting.GameWindowType;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.setting.LauncherVisibility;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests complete global-preset editing, selection, and default-preset commands in the Swing settings page.
@NotNullByDefault
public final class GameSettingsPresetsPanelTest {
    /// Edits every settings group in a selected preset and makes it the default through the store contract.
    @Test
    public void savesCompleteSelectedPresetAndChangesDefault() {
        GameSettingsPresetSnapshot first = preset("1", "Default", true);
        GameSettingsPresetSnapshot second = preset("2", "Performance", false);
        FakeGameSettingsPresetsStore store = new FakeGameSettingsPresetsStore(snapshot(1L, first, second));
        GameSettingsPresetsPanel panel = onEventDispatchThread(
                () -> new GameSettingsPresetsPanel(store, new StaticJavaRuntimeManagementService()));

        onEventDispatchThread(() -> {
            JList<?> presets = findComponent(panel, "gameSettingsPresetList", JList.class);
            presets.setSelectedIndex(1);
            findComponent(panel, "gameSettingsPresetDefault", AbstractButton.class).doClick();

            assertCompleteGlobalEditorSurface(panel);
            editEverySettingsGroup(panel);
            findComponent(panel, "gameSettingsPresetIsolation", JComboBox.class)
                    .setSelectedItem(DefaultIsolationType.NEVER);
            findComponent(panel, "gameSettingsPresetSave", AbstractButton.class).doClick();

            @Nullable GameSettingsPresetEditor saved = store.lastEditor.get();
            assertNotNull(saved);
            assertAll(
                    () -> assertEquals(second.id(), store.defaultPreset.get()),
                    () -> assertEquals(second.id(), saved.id()),
                    () -> assertEquals(
                            new GameSettingsPresetEditor.MemorySettings(false, 6144),
                            saved.memory()),
                    () -> assertEquals(
                            new GameSettingsPresetEditor.JavaRuntimeSettings(
                                    JavaVersionType.CUSTOM,
                                    "23",
                                    "C:/Java/23/bin/java.exe",
                                    GameSettings.DetectedJava.EMPTY),
                            saved.javaRuntime()),
                    () -> assertEquals(
                            new GameSettingsPresetEditor.WindowSettings(
                                    GameWindowType.WINDOWED,
                                    1280.0D,
                                    720.0D),
                            saved.window()),
                    () -> assertEquals(
                            new GameSettingsPresetEditor.LauncherSettings(
                                    LauncherVisibility.HIDE_AND_REOPEN,
                                    false,
                                    true,
                                    true,
                                    true,
                                    true),
                            saved.launcher()),
                    () -> assertEquals(
                            new GameSettingsPresetEditor.QuickPlaySettings(
                                    QuickPlayType.MULTIPLAYER,
                                    "play.example.org:25565",
                                    "World_One",
                                    "realm-7"),
                            saved.quickPlay()),
                    () -> assertEquals(
                            new GameSettingsPresetEditor.LaunchOptionsSettings(
                                    "C:/Games/Performance",
                                    "--demo",
                                    "XYML_TEST=1",
                                    ProcessPriority.HIGH),
                            saved.launchOptions()),
                    () -> assertEquals(
                            new GameSettingsPresetEditor.JvmSettings(
                                    true,
                                    true,
                                    true,
                                    "-XX:+UseZGC",
                                    768,
                                    "384"),
                            saved.jvm()),
                    () -> assertEquals(
                            new GameSettingsPresetEditor.CommandSettings(
                                    "echo pre",
                                    "wrapper --flag",
                                    "echo post"),
                            saved.commands()),
                    () -> assertEquals(
                            new GameSettingsPresetEditor.GraphicsSettings(
                                    GraphicsAPI.VULKAN,
                                    Renderer.DEFAULT,
                                    Renderer.DEFAULT),
                            saved.graphics()),
                    () -> assertEquals(
                            new GameSettingsPresetEditor.NativeLibrarySettings(
                                    true,
                                    "C:/Games/Performance/natives",
                                    true,
                                    true,
                                    true),
                            saved.nativeLibraries()),
                    () -> assertEquals(DefaultIsolationType.NEVER, saved.defaultIsolationType()));
            panel.close();
        });
    }

    /// Confirms historical numeric sentinels and invalid inactive text survive unrelated complete saves unchanged.
    @Test
    public void preservesHistoricalAndInactiveValuesWhenUnedited() {
        assertHistoricalPresetRoundTrip("3", 0);
        assertHistoricalPresetRoundTrip("4", null);
    }

    /// Keeps the preset list and complete embedded editor disabled until an asynchronous save actually completes.
    @Test
    public void disablesSelectionAndEditorWhileSaveIsPending() {
        GameSettingsPresetSnapshot initial = preset("5", "Delayed", true);
        FakeGameSettingsPresetsStore store = new FakeGameSettingsPresetsStore(snapshot(1L, initial));
        store.delayNextUpdate();
        GameSettingsPresetsPanel panel = onEventDispatchThread(
                () -> new GameSettingsPresetsPanel(store, new StaticJavaRuntimeManagementService()));

        onEventDispatchThread(() -> {
            JList<?> presetList = findComponent(panel, "gameSettingsPresetList", JList.class);
            JTabbedPane tabs = findComponent(panel, "globalGameSettingsPresetTabs", JTabbedPane.class);
            JComboBox<?> launcherVisibility =
                    findComponent(panel, "instanceGameSettingsLauncherVisibility", JComboBox.class);
            assertAll(
                    () -> assertTrue(presetList.isEnabled()),
                    () -> assertTrue(tabs.isEnabled()),
                    () -> assertTrue(launcherVisibility.isEnabled()));

            findComponent(panel, "gameSettingsPresetSave", AbstractButton.class).doClick();
            assertAll(
                    () -> assertFalse(presetList.isEnabled()),
                    () -> assertFalse(tabs.isEnabled()),
                    () -> assertFalse(launcherVisibility.isEnabled()));
            store.completePendingUpdate();
        });

        onEventDispatchThread(() -> {
        });
        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertTrue(findComponent(panel, "gameSettingsPresetList", JList.class).isEnabled()),
                    () -> assertTrue(findComponent(
                            panel,
                            "globalGameSettingsPresetTabs",
                            JTabbedPane.class).isEnabled()),
                    () -> assertTrue(findComponent(
                            panel,
                            "instanceGameSettingsLauncherVisibility",
                            JComboBox.class).isEnabled()));
            panel.close();
        });
    }

    /// Keeps all six settings pages navigable while a read-only snapshot disables value editors and mutations.
    @Test
    public void keepsTabsNavigableForReadOnlyPresets() {
        GameSettingsPresetSnapshot initial = preset("6", "Read only", true);
        FakeGameSettingsPresetsStore store = new FakeGameSettingsPresetsStore(
                new GameSettingsPresetsSnapshot(1L, false, List.of(initial)));
        GameSettingsPresetsPanel panel = onEventDispatchThread(
                () -> new GameSettingsPresetsPanel(store, new StaticJavaRuntimeManagementService()));

        onEventDispatchThread(() -> {
            JTabbedPane tabs = findComponent(panel, "globalGameSettingsPresetTabs", JTabbedPane.class);
            JComboBox<?> launcherVisibility =
                    findComponent(panel, "instanceGameSettingsLauncherVisibility", JComboBox.class);

            assertAll(
                    () -> assertTrue(tabs.isEnabled()),
                    () -> assertEquals(6, tabs.getTabCount()),
                    () -> assertFalse(launcherVisibility.isEnabled()),
                    () -> assertFalse(findComponent(
                            panel,
                            "gameSettingsPresetSave",
                            AbstractButton.class).isEnabled()));
            tabs.setSelectedIndex(5);
            assertEquals(5, tabs.getSelectedIndex());
            panel.close();
        });
    }

    /// Exercises one historical preset through real control construction and the owning panel save boundary.
    ///
    /// @param suffix deterministic UUID suffix
    /// @param maximumMemory nullable or non-positive historical maximum heap value
    private static void assertHistoricalPresetRoundTrip(String suffix, @Nullable Integer maximumMemory) {
        String invalidPath = "invalid" + (char) 0 + "path";
        GameSettingsPresetID id =
                GameSettingsPresetID.parse("game-settings-preset:123e4567-e89b-12d3-a456-42661417400" + suffix);
        GameSettingsPresetEditor original = historicalEditor(id, maximumMemory, invalidPath);
        GameSettingsPresetSnapshot initial = new GameSettingsPresetSnapshot(
                id,
                "Historical",
                "Historical",
                null,
                true,
                original);
        FakeGameSettingsPresetsStore store = new FakeGameSettingsPresetsStore(snapshot(1L, initial));
        GameSettingsPresetsPanel panel = onEventDispatchThread(
                () -> new GameSettingsPresetsPanel(store, new StaticJavaRuntimeManagementService()));

        onEventDispatchThread(() -> {
            setBoolean(panel, "instanceGameSettingsShowLogs", true);
            findComponent(panel, "gameSettingsPresetSave", AbstractButton.class).doClick();

            @Nullable GameSettingsPresetEditor saved = store.lastEditor.get();
            assertNotNull(saved);
            assertAll(
                    () -> assertEquals(maximumMemory, saved.memory().maximumMiB()),
                    () -> assertEquals(-640.0D, saved.window().width()),
                    () -> assertEquals(-360.0D, saved.window().height()),
                    () -> assertEquals(Integer.valueOf(-256), saved.jvm().minimumMemoryMiB()),
                    () -> assertEquals(JavaVersionType.AUTO, saved.javaRuntime().type()),
                    () -> assertEquals("not-a-version", saved.javaRuntime().customVersion()),
                    () -> assertEquals(invalidPath, saved.javaRuntime().customPath()),
                    () -> assertEquals(QuickPlayType.NONE, saved.quickPlay().type()),
                    () -> assertEquals("host:99999", saved.quickPlay().multiplayer()),
                    () -> assertEquals("world/name", saved.quickPlay().singleplayer()),
                    () -> assertFalse(saved.nativeLibraries().customDirectoryEnabled()),
                    () -> assertEquals(invalidPath, saved.nativeLibraries().directory()),
                    () -> assertTrue(saved.launcher().showLogs()));
            panel.close();
        });
    }

    /// Verifies the global presentation exposes all six shared setting tabs without instance override controls.
    ///
    /// @param panel global preset panel under test
    private static void assertCompleteGlobalEditorSurface(GameSettingsPresetsPanel panel) {
        JTabbedPane tabs = findComponent(panel, "globalGameSettingsPresetTabs", JTabbedPane.class);
        assertEquals(6, tabs.getTabCount());
        for (String editorName : globalEditorNames()) {
            assertNotNull(findComponent(panel, editorName, Component.class), editorName);
            JCheckBox override = findComponent(panel, editorName + "Override", JCheckBox.class);
            assertAll(
                    () -> assertTrue(override.isSelected(), editorName),
                    () -> assertFalse(override.isVisible(), editorName));
        }
        for (JavaVersionType mode : List.of(
                JavaVersionType.AUTO,
                JavaVersionType.VERSION,
                JavaVersionType.CUSTOM,
                JavaVersionType.DETECTED)) {
            AbstractButton button = findComponent(
                    panel,
                    "instanceGameSettingsJavaMode" + mode.name(),
                    AbstractButton.class);
            assertNull(button.getClientProperty("JButton.buttonType"), mode.name());
        }
        assertNull(findOptionalComponent(
                panel,
                "instanceGameSettingsJavaModeInherit",
                AbstractButton.class));
        for (String editorName : List.of(
                "instanceGameSettingsJavaVersion",
                "instanceGameSettingsJavaPath",
                "instanceGameSettingsDetectedJava")) {
            assertNotNull(findComponent(panel, editorName, Component.class), editorName);
            assertNull(findOptionalComponent(panel, editorName + "Override", JCheckBox.class), editorName);
        }
    }

    /// Writes valid non-default values to every complete global-preset settings group.
    ///
    /// @param panel global preset panel under test
    private static void editEverySettingsGroup(GameSettingsPresetsPanel panel) {
        setBoolean(panel, "instanceGameSettingsAutomaticMemory", false);
        setText(panel, "instanceGameSettingsMaximumMemory", "6144");

        setJavaMode(panel, JavaVersionType.CUSTOM);
        setText(panel, "instanceGameSettingsJavaVersion", "23");
        setText(panel, "instanceGameSettingsJavaPath", "C:/Java/23/bin/java.exe");

        setChoice(panel, "instanceGameSettingsWindowType", GameWindowType.WINDOWED);
        setText(panel, "instanceGameSettingsWindowWidth", "1280");
        setText(panel, "instanceGameSettingsWindowHeight", "720");

        JComboBox<?> launcherVisibility =
                findComponent(panel, "instanceGameSettingsLauncherVisibility", JComboBox.class);
        assertAll(
                () -> assertEquals(4, launcherVisibility.getItemCount()),
                () -> assertEquals(LauncherVisibility.CLOSE, launcherVisibility.getItemAt(0)),
                () -> assertEquals(LauncherVisibility.HIDE, launcherVisibility.getItemAt(1)),
                () -> assertEquals(LauncherVisibility.KEEP, launcherVisibility.getItemAt(2)),
                () -> assertEquals(LauncherVisibility.HIDE_AND_REOPEN, launcherVisibility.getItemAt(3)));
        launcherVisibility.setSelectedItem(LauncherVisibility.HIDE_AND_REOPEN);
        setBoolean(panel, "instanceGameSettingsAllowAutoAgent", false);
        setBoolean(panel, "instanceGameSettingsDisableAutoGameOptions", true);
        setBoolean(panel, "instanceGameSettingsShowLogs", true);
        setBoolean(panel, "instanceGameSettingsDebugLog", true);
        setBoolean(panel, "instanceGameSettingsSkipGameCheck", true);

        setChoice(panel, "instanceGameSettingsQuickPlayMode", QuickPlayType.MULTIPLAYER);
        setText(panel, "instanceGameSettingsQuickPlayMultiplayer", "play.example.org:25565");
        setText(panel, "instanceGameSettingsQuickPlaySingleplayer", "World_One");
        setText(panel, "instanceGameSettingsQuickPlayRealms", "realm-7");

        setText(panel, "instanceGameSettingsRunningDirectory", "C:/Games/Performance");
        setText(panel, "instanceGameSettingsGameArguments", "--demo");
        setText(panel, "instanceGameSettingsEnvironmentVariables", "XYML_TEST=1");
        setChoice(panel, "instanceGameSettingsProcessPriority", ProcessPriority.HIGH);

        setBoolean(panel, "instanceGameSettingsNoOptimizingJvmOptions", true);
        setBoolean(panel, "instanceGameSettingsSkipJvmCheck", true);
        setTextArea(panel, "instanceGameSettingsJvmOptions", "-XX:+UseZGC");
        setText(panel, "instanceGameSettingsMinimumMemory", "768");
        setText(panel, "instanceGameSettingsPermanentGeneration", "384");
        setBoolean(panel, "instanceGameSettingsNoJvmOptions", true);

        setText(panel, "instanceGameSettingsPreLaunchCommand", "echo pre");
        setText(panel, "instanceGameSettingsCommandWrapper", "wrapper --flag");
        setText(panel, "instanceGameSettingsPostExitCommand", "echo post");

        setChoice(panel, "instanceGameSettingsGraphicsBackend", GraphicsAPI.VULKAN);

        setBoolean(panel, "instanceGameSettingsUseCustomNatives", true);
        setText(panel, "instanceGameSettingsNativesDirectory", "C:/Games/Performance/natives");
        setBoolean(panel, "instanceGameSettingsDisableNativePatching", true);
        setBoolean(panel, "instanceGameSettingsUseNativeGlfw", true);
        setBoolean(panel, "instanceGameSettingsUseNativeOpenAl", true);
    }

    /// Returns every editor name represented by the complete global preset snapshot.
    ///
    /// @return immutable stable editor-name list
    private static @Unmodifiable List<String> globalEditorNames() {
        return List.of(
                "instanceGameSettingsAutomaticMemory",
                "instanceGameSettingsMaximumMemory",
                "instanceGameSettingsWindowType",
                "instanceGameSettingsWindowWidth",
                "instanceGameSettingsWindowHeight",
                "instanceGameSettingsLauncherVisibility",
                "instanceGameSettingsAllowAutoAgent",
                "instanceGameSettingsDisableAutoGameOptions",
                "instanceGameSettingsShowLogs",
                "instanceGameSettingsDebugLog",
                "instanceGameSettingsSkipGameCheck",
                "instanceGameSettingsQuickPlayMode",
                "instanceGameSettingsQuickPlayMultiplayer",
                "instanceGameSettingsQuickPlaySingleplayer",
                "instanceGameSettingsQuickPlayRealms",
                "instanceGameSettingsRunningDirectory",
                "instanceGameSettingsGameArguments",
                "instanceGameSettingsEnvironmentVariables",
                "instanceGameSettingsProcessPriority",
                "instanceGameSettingsNoJvmOptions",
                "instanceGameSettingsNoOptimizingJvmOptions",
                "instanceGameSettingsSkipJvmCheck",
                "instanceGameSettingsJvmOptions",
                "instanceGameSettingsMinimumMemory",
                "instanceGameSettingsPermanentGeneration",
                "instanceGameSettingsPreLaunchCommand",
                "instanceGameSettingsCommandWrapper",
                "instanceGameSettingsPostExitCommand",
                "instanceGameSettingsGraphicsBackend",
                "instanceGameSettingsOpenGlRenderer",
                "instanceGameSettingsVulkanRenderer",
                "instanceGameSettingsUseCustomNatives",
                "instanceGameSettingsNativesDirectory",
                "instanceGameSettingsDisableNativePatching",
                "instanceGameSettingsUseNativeGlfw",
                "instanceGameSettingsUseNativeOpenAl");
    }

    /// Selects one checkbox value through its normal action path.
    ///
    /// @param panel containing panel
    /// @param name stable component name
    /// @param selected desired value
    private static void setBoolean(GameSettingsPresetsPanel panel, String name, boolean selected) {
        JCheckBox checkbox = findComponent(panel, name, JCheckBox.class);
        if (checkbox.isSelected() != selected) {
            checkbox.doClick();
        }
    }

    /// Writes one single-line editor value.
    ///
    /// @param panel containing panel
    /// @param name stable component name
    /// @param value desired text
    private static void setText(GameSettingsPresetsPanel panel, String name, String value) {
        findComponent(panel, name, JTextField.class).setText(Objects.requireNonNull(value, "value"));
    }

    /// Writes one multiline editor value.
    ///
    /// @param panel containing panel
    /// @param name stable component name
    /// @param value desired text
    private static void setTextArea(GameSettingsPresetsPanel panel, String name, String value) {
        findComponent(panel, name, JTextArea.class).setText(Objects.requireNonNull(value, "value"));
    }

    /// Selects one highlighted Java strategy.
    ///
    /// @param panel containing panel
    /// @param mode desired Java strategy
    private static void setJavaMode(GameSettingsPresetsPanel panel, JavaVersionType mode) {
        findComponent(
                panel,
                "instanceGameSettingsJavaMode" + Objects.requireNonNull(mode, "mode").name(),
                AbstractButton.class).doClick();
    }

    /// Selects one combo-box value.
    ///
    /// @param panel containing panel
    /// @param name stable component name
    /// @param value desired selection
    private static void setChoice(GameSettingsPresetsPanel panel, String name, Object value) {
        findComponent(panel, name, JComboBox.class).setSelectedItem(Objects.requireNonNull(value, "value"));
    }

    /// Creates a deterministic test snapshot from supplied preset entries.
    ///
    /// @param revision snapshot revision
    /// @param presets ordered preset entries
    /// @return immutable writable test state
    private static GameSettingsPresetsSnapshot snapshot(
            long revision,
            GameSettingsPresetSnapshot @Unmodifiable ... presets) {
        return new GameSettingsPresetsSnapshot(revision, true, List.of(presets));
    }

    /// Creates one reusable preset fixture containing every global settings group.
    ///
    /// @param suffix deterministic UUID suffix
    /// @param name visible preset name
    /// @param defaultPreset whether the fixture is initially default
    /// @return immutable preset fixture
    private static GameSettingsPresetSnapshot preset(String suffix, String name, boolean defaultPreset) {
        GameSettingsPresetID id =
                GameSettingsPresetID.parse("game-settings-preset:123e4567-e89b-12d3-a456-42661417400" + suffix);
        return new GameSettingsPresetSnapshot(
                id,
                name,
                name,
                null,
                defaultPreset,
                new GameSettingsPresetEditor(
                        id,
                        new GameSettingsPresetEditor.MemorySettings(true, 4096),
                        new GameSettingsPresetEditor.JavaRuntimeSettings(
                                JavaVersionType.AUTO,
                                "17",
                                "",
                                GameSettings.DetectedJava.EMPTY),
                        new GameSettingsPresetEditor.WindowSettings(
                                GameWindowType.WINDOWED,
                                854.0D,
                                480.0D),
                        new GameSettingsPresetEditor.LauncherSettings(
                                LauncherVisibility.HIDE,
                                true,
                                false,
                                false,
                                false,
                                false),
                        new GameSettingsPresetEditor.QuickPlaySettings(QuickPlayType.NONE, "", "", ""),
                        new GameSettingsPresetEditor.LaunchOptionsSettings(
                                "",
                                "",
                                "",
                                ProcessPriority.NORMAL),
                        new GameSettingsPresetEditor.JvmSettings(false, false, false, "", null, ""),
                        new GameSettingsPresetEditor.CommandSettings("", "", ""),
                        new GameSettingsPresetEditor.GraphicsSettings(
                                GraphicsAPI.DEFAULT,
                                Renderer.DEFAULT,
                                Renderer.DEFAULT),
                        new GameSettingsPresetEditor.NativeLibrarySettings(false, "", false, false, false),
                        DefaultIsolationType.MODDED));
    }

    /// Creates a complete preset carrying tolerated legacy numeric and inactive-text values.
    ///
    /// @param id preset identity
    /// @param maximumMemory nullable or non-positive historical maximum heap value
    /// @param invalidPath path text that cannot be parsed on any supported platform
    /// @return complete raw historical editor value
    private static GameSettingsPresetEditor historicalEditor(
            GameSettingsPresetID id,
            @Nullable Integer maximumMemory,
            String invalidPath) {
        String path = Objects.requireNonNull(invalidPath, "invalidPath");
        return new GameSettingsPresetEditor(
                Objects.requireNonNull(id, "id"),
                new GameSettingsPresetEditor.MemorySettings(false, maximumMemory),
                new GameSettingsPresetEditor.JavaRuntimeSettings(
                        JavaVersionType.AUTO,
                        "not-a-version",
                        path,
                        GameSettings.DetectedJava.EMPTY),
                new GameSettingsPresetEditor.WindowSettings(GameWindowType.FULLSCREEN, -640.0D, -360.0D),
                new GameSettingsPresetEditor.LauncherSettings(
                        LauncherVisibility.HIDE,
                        true,
                        false,
                        false,
                        false,
                        false),
                new GameSettingsPresetEditor.QuickPlaySettings(
                        QuickPlayType.NONE,
                        "host:99999",
                        "world/name",
                        ""),
                new GameSettingsPresetEditor.LaunchOptionsSettings("", "", "", ProcessPriority.NORMAL),
                new GameSettingsPresetEditor.JvmSettings(false, false, false, "", -256, ""),
                new GameSettingsPresetEditor.CommandSettings("", "", ""),
                new GameSettingsPresetEditor.GraphicsSettings(
                        GraphicsAPI.DEFAULT,
                        Renderer.DEFAULT,
                        Renderer.DEFAULT),
                new GameSettingsPresetEditor.NativeLibrarySettings(false, path, false, false, false),
                DefaultIsolationType.MODDED);
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

    /// Runs an action on the Swing EDT.
    ///
    /// @param action EDT-bound action
    private static void onEventDispatchThread(Runnable action) {
        EdtDispatcher.executeAndWait(Objects.requireNonNull(action, "action"));
    }

    /// Finds one named component in a Swing hierarchy.
    ///
    /// @param root hierarchy root
    /// @param name component name
    /// @param type expected type
    /// @param <T> expected type
    /// @return matching component
    private static <T extends Component> T findComponent(Container root, String name, Class<T> type) {
        @Nullable T result = findOptionalComponent(root, name, type);
        if (result == null) {
            throw new IllegalArgumentException("Missing component: " + name);
        }
        return result;
    }

    /// Searches one Swing hierarchy without throwing when the component is absent.
    ///
    /// @param root hierarchy root
    /// @param name component name
    /// @param type expected type
    /// @param <T> expected type
    /// @return matching component, or null when absent
    private static <T extends Component> @Nullable T findOptionalComponent(
            Container root,
            String name,
            Class<T> type) {
        for (Component child : root.getComponents()) {
            if (Objects.equals(name, child.getName()) && type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                @Nullable T nested = findOptionalComponent(container, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// Supplies an initialized empty Java snapshot without accessing process-wide launcher configuration.
    @NotNullByDefault
    private static final class StaticJavaRuntimeManagementService implements JavaRuntimeManagementService {
        /// Immutable empty runtime state returned to the global editor.
        private final JavaRuntimeManagementSnapshot snapshot =
                new JavaRuntimeManagementSnapshot(true, 0L, List.of());

        /// Returns the deterministic empty runtime state.
        ///
        /// @return initialized empty Java runtime snapshot
        @Override
        public JavaRuntimeManagementSnapshot snapshot() {
            return snapshot;
        }

        /// Accepts the panel subscription without publishing runtime changes.
        ///
        /// @param listener listener retained only for null validation
        /// @return independently removable no-op subscription
        @Override
        public Subscription subscribe(ValueChangeListener<JavaRuntimeManagementSnapshot> listener) {
            Objects.requireNonNull(listener, "listener");
            return Subscription.create(() -> {
            });
        }

        /// Leaves the deterministic empty runtime state unchanged.
        @Override
        public void refreshLocalRuntimes() {
        }

        /// Rejects runtime registration outside this focused preset test.
        ///
        /// @param selectedPath unused selected path
        /// @return never returns because the operation is outside this test
        @Override
        public Task<JavaRuntime> addLocalRuntime(Path selectedPath) {
            throw unsupportedOperation(selectedPath);
        }

        /// Rejects runtime disabling outside this focused preset test.
        ///
        /// @param runtime unused runtime
        /// @return never returns because the operation is outside this test
        @Override
        public Task<@Nullable Void> disableLocalRuntime(JavaRuntime runtime) {
            throw unsupportedOperation(runtime);
        }

        /// Rejects managed runtime removal outside this focused preset test.
        ///
        /// @param runtime unused runtime
        /// @return never returns because the operation is outside this test
        @Override
        public Task<@Nullable Void> uninstallManagedRuntime(JavaRuntime runtime) {
            throw unsupportedOperation(runtime);
        }

        /// Rejects disabled-runtime inspection outside this focused preset test.
        ///
        /// @param disabledRuntime unused disabled runtime
        /// @return never returns because the operation is outside this test
        @Override
        public Task<DisabledJavaRuntimeEntry> inspectDisabledRuntime(DisabledJavaRuntimeEntry disabledRuntime) {
            throw unsupportedOperation(disabledRuntime);
        }

        /// Rejects disabled-runtime restoration outside this focused preset test.
        ///
        /// @param disabledRuntime unused disabled runtime
        /// @return never returns because the operation is outside this test
        @Override
        public Task<JavaRuntime> restoreDisabledRuntime(DisabledJavaRuntimeEntry disabledRuntime) {
            throw unsupportedOperation(disabledRuntime);
        }

        /// Rejects disabled-runtime removal outside this focused preset test.
        ///
        /// @param disabledRuntime unused disabled runtime
        /// @return never returns because the operation is outside this test
        @Override
        public Task<@Nullable Void> removeDisabledRuntime(DisabledJavaRuntimeEntry disabledRuntime) {
            throw unsupportedOperation(disabledRuntime);
        }

        /// Creates the deterministic failure used by unsupported lifecycle operations.
        ///
        /// @param value argument checked for null before failing
        /// @return unsupported-operation exception
        private static UnsupportedOperationException unsupportedOperation(Object value) {
            Objects.requireNonNull(value, "value");
            return new UnsupportedOperationException("Not used by this test");
        }
    }

    /// In-memory store that lets the panel test observe submitted command values without global state.
    @NotNullByDefault
    private static final class FakeGameSettingsPresetsStore implements GameSettingsPresetsStore {
        /// Publishes immutable snapshots to panel listeners.
        private final ValueChangeSupport<GameSettingsPresetsSnapshot> changes = new ValueChangeSupport<>(this);

        /// Most recently published immutable state.
        private GameSettingsPresetsSnapshot snapshot;

        /// Most recently saved editor, or null before a save command.
        private final AtomicReference<@Nullable GameSettingsPresetEditor> lastEditor = new AtomicReference<>();

        /// Default preset changed through the fake command surface, or null before selection.
        private final AtomicReference<@Nullable GameSettingsPresetID> defaultPreset = new AtomicReference<>();

        /// Delayed update completion requested by one pending-state test, or null for immediate commands.
        private @Nullable CompletableFuture<GameSettingsPresetsSnapshot> pendingUpdate;

        /// Snapshot to publish immediately before the delayed update completes, or null when no update is pending.
        private @Nullable GameSettingsPresetsSnapshot pendingUpdateSnapshot;

        /// Creates a fake store with one initial immutable snapshot.
        ///
        /// @param snapshot starting state
        private FakeGameSettingsPresetsStore(GameSettingsPresetsSnapshot snapshot) {
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }

        /// Makes the next update command remain incomplete until [#completePendingUpdate()] is called.
        private void delayNextUpdate() {
            if (pendingUpdate != null) {
                throw new IllegalStateException("An update is already delayed");
            }
            pendingUpdate = new CompletableFuture<>();
        }

        /// Publishes and completes the update previously delayed by [#delayNextUpdate()].
        private void completePendingUpdate() {
            CompletableFuture<GameSettingsPresetsSnapshot> completion = Objects.requireNonNull(
                    pendingUpdate,
                    "No update is pending");
            GameSettingsPresetsSnapshot replacement = Objects.requireNonNull(
                    pendingUpdateSnapshot,
                    "Pending update has not been submitted");
            pendingUpdate = null;
            pendingUpdateSnapshot = null;
            publish(replacement);
            completion.complete(snapshot);
        }

        /// Returns the current immutable state.
        ///
        /// @return current snapshot
        @Override
        public GameSettingsPresetsSnapshot snapshot() {
            return snapshot;
        }

        /// Registers a snapshot listener.
        ///
        /// @param listener target listener
        /// @return independently removable registration
        @Override
        public Subscription subscribe(ValueChangeListener<GameSettingsPresetsSnapshot> listener) {
            return changes.subscribe(Objects.requireNonNull(listener, "listener"));
        }

        /// Rejects creation because this focused test does not open modal dialogs.
        ///
        /// @param name ignored requested name
        /// @return failed command stage
        @Override
        public CompletionStage<GameSettingsPresetsSnapshot> createPreset(String name) {
            return unsupported();
        }

        /// Rejects rename because this focused test does not open modal dialogs.
        ///
        /// @param id ignored preset ID
        /// @param name ignored requested name
        /// @return failed command stage
        @Override
        public CompletionStage<GameSettingsPresetsSnapshot> renamePreset(GameSettingsPresetID id, String name) {
            return unsupported();
        }

        /// Rejects deletion because this focused test does not open modal dialogs.
        ///
        /// @param id ignored preset ID
        /// @return failed command stage
        @Override
        public CompletionStage<GameSettingsPresetsSnapshot> deletePreset(GameSettingsPresetID id) {
            return unsupported();
        }

        /// Updates the default flag in the fake snapshot and publishes the change.
        ///
        /// @param id selected default ID
        /// @return completed updated snapshot stage
        @Override
        public CompletionStage<GameSettingsPresetsSnapshot> setDefaultPreset(GameSettingsPresetID id) {
            defaultPreset.set(Objects.requireNonNull(id, "id"));
            publish(replacePreset(id, null, true));
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Captures editor values and publishes a snapshot retaining every complete settings group.
        ///
        /// @param editor values saved by the panel
        /// @return completed updated snapshot stage
        @Override
        public CompletionStage<GameSettingsPresetsSnapshot> updatePreset(GameSettingsPresetEditor editor) {
            GameSettingsPresetEditor values = Objects.requireNonNull(editor, "editor");
            lastEditor.set(values);
            GameSettingsPresetsSnapshot replacement = replacePreset(values.id(), values, false);
            @Nullable CompletableFuture<GameSettingsPresetsSnapshot> delayed = pendingUpdate;
            if (delayed != null) {
                pendingUpdateSnapshot = replacement;
                return delayed;
            }
            publish(replacement);
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Does not retain external resources in the test fake.
        @Override
        public void close() {
        }

        /// Returns a failed stage for commands outside this focused panel test.
        ///
        /// @return failed command stage
        private static CompletionStage<GameSettingsPresetsSnapshot> unsupported() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("Not used by this test"));
        }

        /// Replaces one preset entry while retaining all unrelated complete editor state.
        ///
        /// @param id target preset ID
        /// @param editor replacement editor values, or null when only changing default state
        /// @param makeDefault whether the target should become the only default
        /// @return replacement snapshot
        private GameSettingsPresetsSnapshot replacePreset(
                GameSettingsPresetID id,
                @Nullable GameSettingsPresetEditor editor,
                boolean makeDefault) {
            List<GameSettingsPresetSnapshot> entries = snapshot.presets().stream()
                    .map(preset -> replacementEntry(preset, id, editor, makeDefault))
                    .toList();
            return new GameSettingsPresetsSnapshot(snapshot.revision() + 1L, true, entries);
        }

        /// Maps one existing entry to its optional complete editor and default-selection replacement.
        ///
        /// @param preset source entry
        /// @param selectedId selected preset ID
        /// @param editor editor values, or null
        /// @param makeDefault whether selected ID becomes the only default
        /// @return mapped entry
        private static GameSettingsPresetSnapshot replacementEntry(
                GameSettingsPresetSnapshot preset,
                GameSettingsPresetID selectedId,
                @Nullable GameSettingsPresetEditor editor,
                boolean makeDefault) {
            boolean selected = preset.id().equals(selectedId);
            GameSettingsPresetEditor replacementEditor = selected && editor != null ? editor : preset.editor();
            return new GameSettingsPresetSnapshot(
                    preset.id(),
                    preset.displayName(),
                    preset.customName(),
                    preset.autoNameNumber(),
                    makeDefault ? selected : preset.defaultPreset(),
                    replacementEditor);
        }

        /// Publishes a replacement immutable state.
        ///
        /// @param replacement next state
        private void publish(GameSettingsPresetsSnapshot replacement) {
            GameSettingsPresetsSnapshot previous = snapshot;
            snapshot = Objects.requireNonNull(replacement, "replacement");
            changes.fireChange(previous, snapshot);
        }
    }
}
