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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.GraphicsAPI;
import space.minecraftstl.xyml.game.ProcessPriority;
import space.minecraftstl.xyml.game.QuickPlayType;
import space.minecraftstl.xyml.game.Renderer;
import space.minecraftstl.xyml.java.JavaInfo;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.setting.GameWindowType;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.setting.LauncherVisibility;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.page.settings.DisabledJavaRuntimeEntry;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaRuntimeManagementService;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaRuntimeManagementSnapshot;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Verifies the complete instance launch-settings page renders and persists independent local overrides.
@NotNullByDefault
final class InstanceGameSettingsPanelTest {
    /// Saves the original four visible setting areas using their new independent override controls.
    @Test
    void savesExplicitLocalOverrides() {
        RecordingStore store = new RecordingStore(snapshot());
        AtomicInteger workingDirectoryChanges = new AtomicInteger();
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(store, workingDirectoryChanges::incrementAndGet);
                panelReference.set(panel);

                selectMemoryMode(panel, false);
                findNamed(panel, "instanceGameSettingsMaximumMemory", JTextField.class).setText("6144");
                overrideJavaMode(panel, JavaVersionType.VERSION);
                findNamed(panel, "instanceGameSettingsJavaVersion", JTextField.class).setText("21");
                clickOverride(panel, "instanceGameSettingsJvmOptions");
                findNamed(panel, "instanceGameSettingsJvmOptions", JTextArea.class).setText("-XX:+UseG1GC");
                findNamed(panel, "instanceGameSettingsIsolation", JCheckBox.class).doClick();
                findNamed(panel, "instanceGameSettingsRunningDirectory", JTextField.class).setText("instance-run");
                findNamed(panel, "instanceGameSettingsSave", JButton.class).doClick();
            });

            assertEquals(1, store.saveCount.get());
            InstanceGameSettingsSnapshot saved = store.snapshot();
            assertTrue(saved.memory().automaticOverridden());
            assertTrue(saved.memory().maximumOverridden());
            assertFalse(saved.memory().automatic());
            assertEquals(6144, saved.memory().maximumMiB());
            assertTrue(saved.javaRuntime().typeOverridden());
            assertTrue(saved.javaRuntime().customVersionOverridden());
            assertFalse(saved.javaRuntime().customPathOverridden());
            assertFalse(saved.javaRuntime().detectedJavaOverridden());
            assertEquals(JavaVersionType.VERSION, saved.javaRuntime().type());
            assertEquals("21", saved.javaRuntime().customVersion());
            assertTrue(saved.jvm().optionsOverridden());
            assertEquals("-XX:+UseG1GC", saved.jvm().options());
            assertTrue(saved.launchOptions().runningDirectoryOverridden());
            assertEquals("instance-run", saved.launchOptions().runningDirectory());
            assertEquals(1, workingDirectoryChanges.get());
        } finally {
            closePanel(panelReference);
        }
    }

    /// Persists explicit isolation as an empty local running directory and restores inheritance when disabled.
    @Test
    void exposesExplicitIsolationControl() {
        RecordingStore store = new RecordingStore(snapshotWithRunningDirectory(false, "shared-run"));
        AtomicInteger workingDirectoryChanges = new AtomicInteger();
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(store, workingDirectoryChanges::incrementAndGet);
                panelReference.set(panel);
                JCheckBox isolation = findNamed(panel, "instanceGameSettingsIsolation", JCheckBox.class);
                JTextField runningDirectory =
                        findNamed(panel, "instanceGameSettingsRunningDirectory", JTextField.class);
                JButton browse = findNamed(panel, "instanceGameSettingsRunningDirectoryBrowse", JButton.class);

                assertFalse(isolation.isSelected());
                assertFalse(runningDirectory.isEnabled());
                assertFalse(browse.isEnabled());

                isolation.doClick();

                assertTrue(isolation.isSelected());
                assertTrue(runningDirectory.isEnabled());
                assertTrue(browse.isEnabled());
                assertEquals("", runningDirectory.getText());
                findNamed(panel, "instanceGameSettingsSave", JButton.class).doClick();
            });

            assertTrue(store.snapshot().launchOptions().runningDirectoryOverridden());
            assertEquals("", store.snapshot().launchOptions().runningDirectory());
            assertEquals(1, workingDirectoryChanges.get());

            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = Objects.requireNonNull(panelReference.get(), "panel");
                findNamed(panel, "instanceGameSettingsIsolation", JCheckBox.class).doClick();
                findNamed(panel, "instanceGameSettingsSave", JButton.class).doClick();
            });

            assertFalse(store.snapshot().launchOptions().runningDirectoryOverridden());
            assertEquals(2, workingDirectoryChanges.get());
        } finally {
            closePanel(panelReference);
        }
    }

    /// Keeps modpack isolation selected and prevents edits without mutating the stored override state.
    ///
    /// @param forcedRoot forced instance root displayed by the page
    @Test
    void forcesIsolationForModpackInstances(@TempDir Path forcedRoot) {
        RecordingStore store = new RecordingStore(
                snapshotWithRunningDirectory(false, "shared-run"),
                forcedRoot.toString());
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(store);
                panelReference.set(panel);

                JCheckBox isolation = findNamed(panel, "instanceGameSettingsIsolation", JCheckBox.class);
                JTextField runningDirectory =
                        findNamed(panel, "instanceGameSettingsRunningDirectory", JTextField.class);
                assertTrue(isolation.isSelected());
                assertFalse(isolation.isEnabled());
                assertFalse(runningDirectory.isEnabled());
                assertEquals(forcedRoot.toString(), runningDirectory.getText());

                findNamed(panel, "instanceGameSettingsSave", JButton.class).doClick();
            });

            assertFalse(store.snapshot().launchOptions().runningDirectoryOverridden());
            assertEquals("shared-run", store.snapshot().launchOptions().runningDirectory());
        } finally {
            closePanel(panelReference);
        }
    }

    /// Resolves inherited values immediately when the instance selects another global preset.
    @Test
    void previewsAndPersistsSelectedParentPreset() {
        GameSettingsPresetID selectedId = GameSettingsPresetID.generate();
        InstanceGameSettingsSnapshot initial = withParentPreset(
                snapshot(),
                new InstanceGameSettingsSnapshot.ParentPresetSettings(
                        null,
                        List.of(
                                new InstanceGameSettingsParentPreset(null, "Default"),
                                new InstanceGameSettingsParentPreset(selectedId, "Performance"))));
        RecordingStore store = new RecordingStore(initial);
        store.previewer = candidate -> withMaximumMemory(candidate, 8192);
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(store);
                panelReference.set(panel);
                JComboBox<?> parentPreset = findNamed(
                        panel,
                        "instanceGameSettingsParentPreset",
                        JComboBox.class);

                parentPreset.setSelectedIndex(1);

                assertEquals(1, store.previewCount.get());
                assertEquals(
                        "8192",
                        findNamed(panel, "instanceGameSettingsMaximumMemory", JTextField.class).getText());
                findNamed(panel, "instanceGameSettingsSave", JButton.class).doClick();
            });

            assertEquals(selectedId, store.snapshot().parentPreset().selectedId());
            assertEquals(1, store.saveCount.get());
        } finally {
            closePanel(panelReference);
        }
    }

    /// Keeps custom Java paths directly editable while writing a chosen executable into the same field.
    @Test
    void choosesCustomJavaExecutableIntoEditablePath() {
        Path selectedPath = Path.of("runtime", "bin", "java");
        EdtDispatcher.executeAndWait(() -> {
            JTextField path = new JTextField();
            InstanceJavaPathControls controls = new InstanceJavaPathControls(
                    path,
                    () -> selectedPath);
            controls.updateAvailability(true);

            findNamed(controls.component(), "instanceGameSettingsJavaPathBrowse", JButton.class).doClick();

            assertTrue(path.isEditable());
            assertEquals(selectedPath.toAbsolutePath().normalize().toString(), path.getText());
        });
    }

    /// Requires explicit consent before invoking backup-and-overwrite recovery for read-only settings.
    @Test
    void confirmsReadOnlySettingsRecovery() {
        RecordingStore store = new RecordingStore(withWritable(snapshot(), false));
        store.forceOverwriteAvailable = true;
        AtomicInteger reloads = new AtomicInteger();
        EdtDispatcher.executeAndWait(() -> {
            InstanceGameSettingsFooterControls footer = new InstanceGameSettingsFooterControls(
                    store,
                    () -> { },
                    reloads::incrementAndGet,
                    () -> true);
            footer.updateAvailability(false, true);

            findNamed(
                    footer.component(),
                    "instanceGameSettingsForceOverwrite",
                    JButton.class).doClick();
        });

        assertEquals(1, store.forceOverwriteCount.get());
        assertEquals(1, reloads.get());
    }

    /// Ensures inherited properties expose overrides while Java uses explicit radio rows without old checkboxes.
    @Test
    void exposesCompleteSettingsSurface() {
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(new RecordingStore(snapshot()));
                panelReference.set(panel);
                JTabbedPane tabs = findNamed(panel, "instanceGameSettingsTabs", JTabbedPane.class);
                assertEquals(6, tabs.getTabCount());
                for (String editorName : editorNames()) {
                    assertNotNull(findNamed(panel, editorName, JComponent.class), editorName);
                    assertNotNull(findNamed(panel, editorName + "Override", JCheckBox.class), editorName);
                }
                for (String mode : List.of("Inherit", "Automatic", "Manual")) {
                    assertNotNull(findNamed(
                            panel,
                            "instanceGameSettingsMemoryMode" + mode,
                            JRadioButton.class));
                }
                assertNotNull(findNamed(panel, "instanceGameSettingsMaximumMemory", JTextField.class));
                assertNull(findNamedNullable(
                        panel,
                        "instanceGameSettingsAutomaticMemoryOverride",
                        JCheckBox.class));
                assertNull(findNamedNullable(
                        panel,
                        "instanceGameSettingsMaximumMemoryOverride",
                        JCheckBox.class));
                for (JavaVersionType mode : InstanceJavaModeSelector.displayOrder()) {
                    assertNotNull(findNamed(
                            panel,
                            "instanceGameSettingsJavaMode" + mode.name(),
                            JRadioButton.class));
                }
                assertNotNull(findNamed(
                        panel,
                        "instanceGameSettingsJavaModeInherit",
                        JRadioButton.class));
                for (String javaEditor : javaEditorNames()) {
                    assertNotNull(findNamed(panel, javaEditor, JComponent.class), javaEditor);
                    assertNull(findNamedNullable(panel, javaEditor + "Override", JCheckBox.class), javaEditor);
                }
            });
        } finally {
            closePanel(panelReference);
        }
    }

    /// Exposes inheritance, automatic allocation, and manual allocation as three native radio choices.
    @Test
    void exposesThreeInstanceMemoryModeChoices() {
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(new RecordingStore(snapshot()));
                panelReference.set(panel);
                JRadioButton inheritance = findNamed(
                        panel,
                        "instanceGameSettingsMemoryModeInherit",
                        JRadioButton.class);
                JRadioButton automatic = findNamed(
                        panel,
                        "instanceGameSettingsMemoryModeAutomatic",
                        JRadioButton.class);
                JRadioButton manual = findNamed(
                        panel,
                        "instanceGameSettingsMemoryModeManual",
                        JRadioButton.class);

                assertTrue(inheritance.isSelected());
                assertEquals(i18n("settings.memory.auto_allocate"), automatic.getText());
                assertEquals(i18n("settings.memory.manual_allocate"), manual.getText());
                automatic.doClick();
                assertTrue(automatic.isSelected());
                manual.doClick();
                assertTrue(manual.isSelected());
                assertFalse(automatic.isSelected());
            });
        } finally {
            closePanel(panelReference);
        }
    }

    /// Keeps every advanced command editor in one stable column with uniform row spacing.
    @Test
    void alignsAdvancedCommandEditors() {
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(new RecordingStore(snapshot()));
                panelReference.set(panel);
                JTabbedPane tabs = findNamed(panel, "instanceGameSettingsTabs", JTabbedPane.class);
                JScrollPane commandsScroll = (JScrollPane) tabs.getComponentAt(3);
                JLabel preLaunchLabel = findNamed(
                        commandsScroll,
                        "instanceGameSettingsPreLaunchCommandLabel",
                        JLabel.class);
                JTextField preLaunch = findNamed(
                        commandsScroll,
                        "instanceGameSettingsPreLaunchCommand",
                        JTextField.class);
                JLabel wrapperLabel = findNamed(
                        commandsScroll,
                        "instanceGameSettingsCommandWrapperLabel",
                        JLabel.class);
                JTextField wrapper = findNamed(
                        commandsScroll,
                        "instanceGameSettingsCommandWrapper",
                        JTextField.class);
                JLabel postExitLabel = findNamed(
                        commandsScroll,
                        "instanceGameSettingsPostExitCommandLabel",
                        JLabel.class);
                JTextField postExit = findNamed(
                        commandsScroll,
                        "instanceGameSettingsPostExitCommand",
                        JTextField.class);
                layoutScrollableTab(commandsScroll, 700, 280);
                assertEquals(preLaunch.getX(), wrapper.getX());
                assertEquals(wrapper.getX(), postExit.getX());
                assertLabelPrecedesEditor(preLaunchLabel, preLaunch);
                assertLabelPrecedesEditor(wrapperLabel, wrapper);
                assertLabelPrecedesEditor(postExitLabel, postExit);
                int commandGap = verticalGap(preLaunch, wrapper);
                assertTrue(commandGap >= 9 && commandGap <= 11);
                assertEquals(commandGap, verticalGap(wrapper, postExit));
                assertTrue(preLaunch.getWidth() >= 180);
                assertEquals(commandsScroll.getViewport().getExtentSize().width,
                        commandsScroll.getViewport().getView().getWidth());
            });
        } finally {
            closePanel(panelReference);
        }
    }

    /// Keeps window and Quick Play rows on the same vertical rhythm and editor column.
    @Test
    void alignsGameSettingRows() {
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(new RecordingStore(snapshot()));
                panelReference.set(panel);
                JTabbedPane tabs = findNamed(panel, "instanceGameSettingsTabs", JTabbedPane.class);
                JScrollPane gameScroll = (JScrollPane) tabs.getComponentAt(0);
                JComboBox<?> windowType = findNamed(
                        gameScroll,
                        "instanceGameSettingsWindowType",
                        JComboBox.class);
                JPanel resolutionRow = findNamed(
                        gameScroll,
                        "instanceGameSettingsWindowSizePresetRow",
                        JPanel.class);
                JComboBox<?> resolution = findNamed(
                        resolutionRow,
                        "instanceGameSettingsWindowSizePreset",
                        JComboBox.class);
                JTextField windowWidth = findNamed(
                        gameScroll,
                        "instanceGameSettingsWindowWidth",
                        JTextField.class);
                JTextField windowHeight = findNamed(
                        gameScroll,
                        "instanceGameSettingsWindowHeight",
                        JTextField.class);
                JComboBox<?> quickPlay = findNamed(
                        gameScroll,
                        "instanceGameSettingsQuickPlayMode",
                        JComboBox.class);
                JTextField multiplayer = findNamed(
                        gameScroll,
                        "instanceGameSettingsQuickPlayMultiplayer",
                        JTextField.class);
                JTextField singleplayer = findNamed(
                        gameScroll,
                        "instanceGameSettingsQuickPlaySingleplayer",
                        JTextField.class);
                JTextField realms = findNamed(
                        gameScroll,
                        "instanceGameSettingsQuickPlayRealms",
                        JTextField.class);
                JLabel runningDirectoryLabel = findNamed(
                        gameScroll,
                        "instanceGameSettingsRunningDirectoryLabel",
                        JLabel.class);
                JPanel runningDirectoryEditor = findNamed(
                        gameScroll,
                        "instanceGameSettingsRunningDirectoryEditor",
                        JPanel.class);

                layoutScrollableTab(gameScroll, 700, 900);
                assertEquals(windowType.getX(), resolutionRow.getX() + resolution.getX());
                assertEquals(windowType.getX(), windowWidth.getX());
                assertEquals(windowWidth.getX(), windowHeight.getX());
                assertUniformRowGaps(
                        verticalGap(windowType, resolutionRow),
                        verticalGap(resolutionRow, windowWidth),
                        verticalGap(windowWidth, windowHeight));
                assertEquals(quickPlay.getX(), multiplayer.getX());
                assertEquals(multiplayer.getX(), singleplayer.getX());
                assertEquals(singleplayer.getX(), realms.getX());
                assertUniformRowGaps(
                        verticalGap(quickPlay, multiplayer),
                        verticalGap(multiplayer, singleplayer),
                        verticalGap(singleplayer, realms));
                assertLabelPrecedesEditor(runningDirectoryLabel, runningDirectoryEditor);
                for (String name : List.of(
                        "instanceGameSettingsGameArguments",
                        "instanceGameSettingsEnvironmentVariables",
                        "instanceGameSettingsProcessPriority")) {
                    JComponent editor = findNamed(gameScroll, name, JComponent.class);
                    JLabel label = findNamed(gameScroll, name + "Label", JLabel.class);
                    JCheckBox override = findNamed(gameScroll, name + "Override", JCheckBox.class);
                    assertLabelPrecedesEditor(label, editor);
                    assertOverridePrecedesSameRow(override, editor);
                }
                JTextField gameArguments = findNamed(
                        gameScroll, "instanceGameSettingsGameArguments", JTextField.class);
                JTextField environment = findNamed(
                        gameScroll, "instanceGameSettingsEnvironmentVariables", JTextField.class);
                findNamed(gameScroll, "instanceGameSettingsGameArgumentsOverride", JCheckBox.class).doClick();
                assertTrue(gameArguments.isEnabled());
                assertFalse(environment.isEnabled());
            });
        } finally {
            closePanel(panelReference);
        }
    }

    /// Enables only the payload editor owned by the selected local Java strategy.
    @Test
    void enablesOnlyTheSelectedJavaModePayload() {
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(new RecordingStore(snapshot()));
                panelReference.set(panel);
                JTextField version = findNamed(panel, "instanceGameSettingsJavaVersion", JTextField.class);
                JTextField path = findNamed(panel, "instanceGameSettingsJavaPath", JTextField.class);
                JComboBox<?> detected = findNamed(panel, "instanceGameSettingsDetectedJava", JComboBox.class);

                for (JavaVersionType mode : InstanceJavaModeSelector.displayOrder()) {
                    overrideJavaMode(panel, mode);
                    assertEquals(mode == JavaVersionType.VERSION, version.isEnabled(), mode.name());
                    assertEquals(mode == JavaVersionType.CUSTOM, path.isEnabled(), mode.name());
                    assertEquals(mode == JavaVersionType.DETECTED, detected.isEnabled(), mode.name());
                }
            });
        } finally {
            closePanel(panelReference);
        }
    }

    /// Converts a payload-only Java override to the equivalent local radio mode without losing its value.
    @Test
    void preservesPayloadOnlyJavaOverrides() {
        InstanceGameSettingsSnapshot.JavaRuntimeSettings javaRuntime =
                new InstanceGameSettingsSnapshot.JavaRuntimeSettings(
                        false,
                        JavaVersionType.CUSTOM,
                        false,
                        "",
                        true,
                        "C:/Java/21/bin/java.exe",
                        false,
                        GameSettings.DetectedJava.EMPTY);
        RecordingStore store = new RecordingStore(snapshotWithJavaRuntime(javaRuntime));
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(store);
                panelReference.set(panel);
                assertTrue(findNamed(
                        panel,
                        "instanceGameSettingsJavaModeCUSTOM",
                        JRadioButton.class).isSelected());
                assertFalse(findNamed(
                        panel,
                        "instanceGameSettingsJavaModeInherit",
                        JRadioButton.class).isSelected());
                findNamed(panel, "instanceGameSettingsSave", JButton.class).doClick();
            });

            InstanceGameSettingsSnapshot.JavaRuntimeSettings saved = store.snapshot().javaRuntime();
            assertTrue(saved.typeOverridden());
            assertTrue(saved.customPathOverridden());
            assertEquals(JavaVersionType.CUSTOM, saved.type());
            assertEquals("C:/Java/21/bin/java.exe", saved.customPath());
        } finally {
            closePanel(panelReference);
        }
    }

    /// Applies the startup discovery result after a cold popup request without blocking the EDT.
    ///
    /// @param tempDirectory existing runtime paths used to create distinct persisted Java identities
    /// @throws IOException if the temporary runtime paths cannot be created
    /// @throws InterruptedException if asynchronous choice conversion is interrupted
    @Test
    void populatesColdDetectedJavaAfterStartupDiscovery(
            @TempDir Path tempDirectory) throws IOException, InterruptedException {
        RecordingJavaRuntimeService javaRuntimeService = new RecordingJavaRuntimeService(
                new JavaRuntimeManagementSnapshot(false, 0L, List.of()));
        JavaRuntime runtime = runtime(Files.createFile(tempDirectory.resolve("java-17")));
        JavaRuntime alternateRuntime = runtime(Files.createFile(tempDirectory.resolve("java-21")));
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = new InstanceGameSettingsPanel(
                        new RecordingStore(snapshot()),
                        javaRuntimeService);
                panelReference.set(panel);
                JComboBox<?> detectedJava = findNamed(
                        panel,
                        "instanceGameSettingsDetectedJava",
                        JComboBox.class);

                assertEquals(0, javaRuntimeService.refreshCount());
                assertEquals(1, javaRuntimeService.subscriberCount());
                openPopup(detectedJava);
                assertEquals(0, javaRuntimeService.refreshCount());
            });

            javaRuntimeService.publish(new JavaRuntimeManagementSnapshot(
                    true,
                    1L,
                    List.of(runtime, alternateRuntime)));
            awaitDetectedJavaItemCount(panelReference, 2);

            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = Objects.requireNonNull(panelReference.get());
                JComboBox<?> detectedJava = findNamed(
                        panel,
                        "instanceGameSettingsDetectedJava",
                        JComboBox.class);
                detectedJava.setSelectedIndex(1);
                Object unsavedSelection = Objects.requireNonNull(detectedJava.getSelectedItem());
                openPopup(detectedJava);
                assertEquals(1, javaRuntimeService.refreshCount());
                assertEquals(unsavedSelection, detectedJava.getSelectedItem());

                panel.close();
                assertEquals(0, javaRuntimeService.subscriberCount());
            });
        } finally {
            closePanel(panelReference);
        }
    }

    /// Defers an already initialized runtime list until its combo popup is explicitly requested.
    ///
    /// @param tempDirectory existing runtime paths used to exercise background identity conversion
    /// @throws IOException if the temporary runtime paths cannot be created
    /// @throws InterruptedException if asynchronous choice conversion is interrupted
    @Test
    void defersInitializedDetectedJavaUntilPopup(
            @TempDir Path tempDirectory) throws IOException, InterruptedException {
        JavaRuntime runtime = runtime(Files.createFile(tempDirectory.resolve("java-17")));
        JavaRuntime alternateRuntime = runtime(Files.createFile(tempDirectory.resolve("java-21")));
        RecordingJavaRuntimeService javaRuntimeService = new RecordingJavaRuntimeService(
                new JavaRuntimeManagementSnapshot(true, 1L, List.of(runtime, alternateRuntime)));
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = new InstanceGameSettingsPanel(
                        new RecordingStore(snapshot()),
                        javaRuntimeService);
                panelReference.set(panel);
                JComboBox<?> detectedJava = findNamed(
                        panel,
                        "instanceGameSettingsDetectedJava",
                        JComboBox.class);
                assertEquals(0, detectedJava.getItemCount());
                assertEquals(0, javaRuntimeService.refreshCount());

                openPopup(detectedJava);
                assertEquals(1, javaRuntimeService.refreshCount());
            });

            awaitDetectedJavaItemCount(panelReference, 2);
        } finally {
            closePanel(panelReference);
        }
    }

    /// Keeps backend selection hidden while enabling Vulkan renderer selection at its first supported snapshot.
    @Test
    void appliesSnapshotOneGraphicsCompatibility() {
        CompletableFuture<GameVersionNumber> gameVersion = new CompletableFuture<>();
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = new InstanceGameSettingsPanel(
                        new RecordingStore(snapshot()),
                        new RecordingJavaRuntimeService(new JavaRuntimeManagementSnapshot(false, 0L, List.of())),
                        gameVersion);
                panelReference.set(panel);
                assertGraphicsVisibility(panel, false, true, false);
            });

            gameVersion.complete(GameVersionNumber.asGameVersion("26.2-snapshot-1"));
            EdtDispatcher.executeAndWait(() -> assertGraphicsVisibility(
                    Objects.requireNonNull(panelReference.get()),
                    false,
                    true,
                    true));
        } finally {
            closePanel(panelReference);
        }
    }

    /// Shows every graphics row once explicit backend selection is supported.
    @Test
    void appliesSnapshotTwoGraphicsCompatibility() {
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new InstanceGameSettingsPanel(
                    new RecordingStore(snapshot()),
                    new RecordingJavaRuntimeService(new JavaRuntimeManagementSnapshot(false, 0L, List.of())),
                    CompletableFuture.completedFuture(GameVersionNumber.asGameVersion("26.2-snapshot-2")))));
            EdtDispatcher.executeAndWait(() -> assertGraphicsVisibility(
                    Objects.requireNonNull(panelReference.get()),
                    true,
                    true,
                    true));
        } finally {
            closePanel(panelReference);
        }
    }

    /// Ignores a late version result after panel lifecycle cleanup.
    @Test
    void ignoresGameVersionAfterClose() {
        CompletableFuture<GameVersionNumber> gameVersion = new CompletableFuture<>();
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = new InstanceGameSettingsPanel(
                        new RecordingStore(snapshot()),
                        new RecordingJavaRuntimeService(new JavaRuntimeManagementSnapshot(false, 0L, List.of())),
                        gameVersion);
                panelReference.set(panel);
                panel.close();
            });
            gameVersion.complete(GameVersionNumber.asGameVersion("26.2-snapshot-2"));
            EdtDispatcher.executeAndWait(() -> assertGraphicsVisibility(
                    Objects.requireNonNull(panelReference.get()),
                    false,
                    true,
                    false));
        } finally {
            closePanel(panelReference);
        }
    }

    /// Saves restored settings from every tab and retains each selected override independently.
    @Test
    void savesRestoredSettingsAcrossEveryTab() {
        RecordingStore store = new RecordingStore(snapshot());
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(store);
                panelReference.set(panel);

                overrideChoice(panel, "instanceGameSettingsWindowType", GameWindowType.FULLSCREEN);
                overrideText(panel, "instanceGameSettingsWindowWidth", "1920");
                overrideText(panel, "instanceGameSettingsWindowHeight", "1080");

                overrideChoice(panel, "instanceGameSettingsLauncherVisibility", LauncherVisibility.KEEP);
                overrideBoolean(panel, "instanceGameSettingsAllowAutoAgent", true);
                overrideBoolean(panel, "instanceGameSettingsDisableAutoGameOptions", true);
                overrideBoolean(panel, "instanceGameSettingsShowLogs", true);
                overrideBoolean(panel, "instanceGameSettingsDebugLog", true);
                overrideBoolean(panel, "instanceGameSettingsSkipGameCheck", true);

                overrideChoice(panel, "instanceGameSettingsQuickPlayMode", QuickPlayType.MULTIPLAYER);
                overrideText(panel, "instanceGameSettingsQuickPlayMultiplayer", "localhost:25565");
                overrideText(panel, "instanceGameSettingsGameArguments", "--demo");
                overrideText(panel, "instanceGameSettingsEnvironmentVariables", "XYML_TEST=1");
                overrideChoice(panel, "instanceGameSettingsProcessPriority", ProcessPriority.HIGH);

                overrideBoolean(panel, "instanceGameSettingsNoJvmOptions", false);
                overrideBoolean(panel, "instanceGameSettingsNoOptimizingJvmOptions", true);
                overrideBoolean(panel, "instanceGameSettingsSkipJvmCheck", true);
                overrideText(panel, "instanceGameSettingsMinimumMemory", "512");
                overrideText(panel, "instanceGameSettingsPermanentGeneration", "256");

                overrideText(panel, "instanceGameSettingsPreLaunchCommand", "echo ready");
                overrideText(panel, "instanceGameSettingsCommandWrapper", "wrapper");
                overrideText(panel, "instanceGameSettingsPostExitCommand", "echo done");

                overrideChoice(panel, "instanceGameSettingsGraphicsBackend", GraphicsAPI.VULKAN);
                clickOverride(panel, "instanceGameSettingsOpenGlRenderer");
                clickOverride(panel, "instanceGameSettingsVulkanRenderer");
                overrideBoolean(panel, "instanceGameSettingsHighPerformanceGpu", true);

                overrideBoolean(panel, "instanceGameSettingsUseCustomNatives", true);
                overrideText(panel, "instanceGameSettingsNativesDirectory", "native-bin");
                overrideBoolean(panel, "instanceGameSettingsDisableNativePatching", true);
                overrideBoolean(panel, "instanceGameSettingsUseNativeGlfw", true);
                overrideBoolean(panel, "instanceGameSettingsUseNativeOpenAl", true);

                findNamed(panel, "instanceGameSettingsSave", JButton.class).doClick();
            });

            assertEquals(1, store.saveCount.get());
            InstanceGameSettingsSnapshot saved = store.snapshot();
            assertEquals(GameWindowType.FULLSCREEN, saved.window().type());
            assertEquals(1920.0D, saved.window().width());
            assertEquals(1080.0D, saved.window().height());
            assertEquals(LauncherVisibility.KEEP, saved.launcher().visibility());
            assertTrue(saved.launcher().allowAutoAgent());
            assertTrue(saved.launcher().disableAutoGameOptions());
            assertTrue(saved.launcher().showLogs());
            assertTrue(saved.launcher().debugLog());
            assertTrue(saved.launcher().notCheckGame());
            assertEquals(QuickPlayType.MULTIPLAYER, saved.quickPlay().type());
            assertEquals("localhost:25565", saved.quickPlay().multiplayer());
            assertEquals("--demo", saved.launchOptions().gameArguments());
            assertEquals("XYML_TEST=1", saved.launchOptions().environmentVariables());
            assertEquals(ProcessPriority.HIGH, saved.launchOptions().priority());
            assertTrue(saved.jvm().noOptimizingOptions());
            assertTrue(saved.jvm().notCheckJvm());
            assertEquals(512, saved.jvm().minimumMemoryMiB());
            assertEquals("256", saved.jvm().permanentGenerationMiB());
            assertEquals("echo ready", saved.commands().preLaunch());
            assertEquals("wrapper", saved.commands().wrapper());
            assertEquals("echo done", saved.commands().postExit());
            assertEquals(GraphicsAPI.VULKAN, saved.graphics().backend());
            assertTrue(saved.graphics().openGlRendererOverridden());
            assertTrue(saved.graphics().vulkanRendererOverridden());
            assertTrue(saved.graphics().highPerformanceOverridden());
            assertTrue(saved.graphics().highPerformance());
            assertTrue(saved.nativeLibraries().customDirectoryEnabled());
            assertEquals("native-bin", saved.nativeLibraries().directory());
            assertTrue(saved.nativeLibraries().patchingDisabled());
            assertTrue(saved.nativeLibraries().nativeGlfw());
            assertTrue(saved.nativeLibraries().nativeOpenAl());
        } finally {
            closePanel(panelReference);
        }
    }

    /// Rejects an invalid maximum-memory input before the store can persist a partial change.
    @Test
    void rejectsInvalidMemoryBeforeSave() {
        RecordingStore store = new RecordingStore(snapshot());
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(store);
                panelReference.set(panel);
                selectMemoryMode(panel, false);
                findNamed(panel, "instanceGameSettingsMaximumMemory", JTextField.class).setText("not-a-number");
                findNamed(panel, "instanceGameSettingsSave", JButton.class).doClick();
                JLabel status = findNamed(panel, "instanceGameSettingsStatus", JLabel.class);
                assertTrue(status.getText().startsWith(i18n("swing.instance_settings.save_failed", "")));
            });
            assertEquals(0, store.saveCount.get());
        } finally {
            closePanel(panelReference);
        }
    }

    /// Selecting the manual radio enables its value editor and persists one normalized local mode.
    @Test
    void selectingManualMemoryModeEnablesEditing() {
        RecordingStore store = new RecordingStore(snapshot());
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(store);
                panelReference.set(panel);
                JRadioButton inheritance = findNamed(
                        panel,
                        "instanceGameSettingsMemoryModeInherit",
                        JRadioButton.class);
                JRadioButton manual = findNamed(
                        panel,
                        "instanceGameSettingsMemoryModeManual",
                        JRadioButton.class);
                JTextField maximum = findNamed(
                        panel,
                        "instanceGameSettingsMaximumMemory",
                        JTextField.class);
                JSlider slider = findNamed(
                        panel,
                        "instanceGameSettingsMaximumMemorySlider",
                        JSlider.class);

                assertTrue(inheritance.isSelected());
                assertFalse(maximum.isEnabled());
                assertFalse(slider.isEnabled());

                manual.doClick();

                assertTrue(manual.isSelected());
                assertFalse(inheritance.isSelected());
                assertTrue(maximum.isEnabled());
                assertTrue(slider.isEnabled());
                maximum.setText("6144");
                findNamed(panel, "instanceGameSettingsSave", JButton.class).doClick();
            });

            assertEquals(1, store.saveCount.get());
            InstanceGameSettingsSnapshot.MemorySettings memory = store.snapshot().memory();
            assertTrue(memory.automaticOverridden());
            assertFalse(memory.automatic());
            assertTrue(memory.maximumOverridden());
            assertEquals(6144, memory.maximumMiB());
        } finally {
            closePanel(panelReference);
        }
    }

    /// Ignores disabled drafts and derives dependent editor state from inherited controller values.
    @Test
    void ignoresDraftsAfterOverrideIsDisabled() {
        RecordingStore store = new RecordingStore(snapshot());
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(store);
                panelReference.set(panel);

                selectMemoryMode(panel, false);
                findNamed(panel, "instanceGameSettingsMaximumMemory", JTextField.class).setText("not-a-number");
                findNamed(
                        panel,
                        "instanceGameSettingsMemoryModeInherit",
                        JRadioButton.class).doClick();

                JRadioButton inheritance = findNamed(
                        panel,
                        "instanceGameSettingsJavaModeInherit",
                        JRadioButton.class);
                assertTrue(inheritance.isSelected());
                overrideJavaMode(panel, JavaVersionType.CUSTOM);
                assertFalse(inheritance.isSelected());
                for (JavaVersionType mode : InstanceJavaModeSelector.displayOrder()) {
                    assertTrue(findNamed(
                            panel,
                            "instanceGameSettingsJavaMode" + mode.name(),
                            JRadioButton.class).isEnabled());
                }
                JTextField javaPath = findNamed(panel, "instanceGameSettingsJavaPath", JTextField.class);
                assertTrue(javaPath.isEnabled());
                inheritance.doClick();
                assertTrue(inheritance.isSelected());
                assertFalse(javaPath.isEnabled());

                clickOverride(panel, "instanceGameSettingsWindowType");
                findNamed(panel, "instanceGameSettingsWindowType", JComboBox.class)
                        .setSelectedItem(GameWindowType.FULLSCREEN);
                clickOverride(panel, "instanceGameSettingsWindowWidth");
                JTextField windowWidth = findNamed(
                        panel,
                        "instanceGameSettingsWindowWidth",
                        JTextField.class);
                assertFalse(windowWidth.isEnabled());
                clickOverride(panel, "instanceGameSettingsWindowType");
                assertTrue(windowWidth.isEnabled());

                clickOverride(panel, "instanceGameSettingsQuickPlayMode");
                findNamed(panel, "instanceGameSettingsQuickPlayMode", JComboBox.class)
                        .setSelectedItem(QuickPlayType.MULTIPLAYER);
                clickOverride(panel, "instanceGameSettingsQuickPlayMultiplayer");
                JTextField multiplayer = findNamed(
                        panel,
                        "instanceGameSettingsQuickPlayMultiplayer",
                        JTextField.class);
                assertTrue(multiplayer.isEnabled());
                clickOverride(panel, "instanceGameSettingsQuickPlayMode");
                assertFalse(multiplayer.isEnabled());

                findNamed(panel, "instanceGameSettingsSave", JButton.class).doClick();
            });

            assertEquals(1, store.saveCount.get());
            InstanceGameSettingsSnapshot saved = store.snapshot();
            assertFalse(saved.memory().maximumOverridden());
            assertEquals(4096, saved.memory().maximumMiB());
            assertFalse(saved.javaRuntime().typeOverridden());
            assertEquals(JavaVersionType.AUTO, saved.javaRuntime().type());
            assertFalse(saved.quickPlay().typeOverridden());
            assertEquals(QuickPlayType.NONE, saved.quickPlay().type());
        } finally {
            closePanel(panelReference);
        }
    }

    /// Preserves exact free-form arguments, environment text, and command whitespace on an unchanged save.
    @Test
    void preservesFreeFormWhitespace() {
        RecordingStore store = new RecordingStore(snapshotWithFreeFormWhitespace());
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(store);
                panelReference.set(panel);
                findNamed(panel, "instanceGameSettingsSave", JButton.class).doClick();
            });

            InstanceGameSettingsSnapshot saved = store.snapshot();
            assertEquals("  --demo  ", saved.launchOptions().gameArguments());
            assertEquals("  XYML_TEST=1  ", saved.launchOptions().environmentVariables());
            assertEquals("\n  -XX:+UseG1GC  \n", saved.jvm().options());
            assertEquals("  echo pre  ", saved.commands().preLaunch());
            assertEquals("  wrapper  ", saved.commands().wrapper());
            assertEquals("  echo post  ", saved.commands().postExit());
        } finally {
            closePanel(panelReference);
        }
    }

    /// Normalizes structured version, address, and path values before persistence.
    @Test
    void normalizesStructuredText() {
        RecordingStore store = new RecordingStore(snapshot());
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(store);
                panelReference.set(panel);
                overrideJavaMode(panel, JavaVersionType.VERSION);
                findNamed(panel, "instanceGameSettingsJavaVersion", JTextField.class).setText(" 21 ");
                overrideChoice(panel, "instanceGameSettingsQuickPlayMode", QuickPlayType.MULTIPLAYER);
                overrideText(panel, "instanceGameSettingsQuickPlayMultiplayer", " localhost:25565 ");
                overrideText(panel, "instanceGameSettingsRunningDirectory", " instance-run ");
                findNamed(panel, "instanceGameSettingsSave", JButton.class).doClick();
            });

            InstanceGameSettingsSnapshot saved = store.snapshot();
            assertEquals("21", saved.javaRuntime().customVersion());
            assertEquals("localhost:25565", saved.quickPlay().multiplayer());
            assertEquals("instance-run", saved.launchOptions().runningDirectory());
        } finally {
            closePanel(panelReference);
        }
    }

    /// Preserves fractional and large existing window dimensions during an unchanged save.
    @Test
    void preservesWindowDimensionPrecisionAndRange() {
        InstanceGameSettingsSnapshot base = snapshot();
        InstanceGameSettingsSnapshot precise = new InstanceGameSettingsSnapshot(
                base.writable(),
                base.parentPreset(),
                base.memory(),
                base.javaRuntime(),
                new InstanceGameSettingsSnapshot.WindowSettings(
                        true,
                        GameWindowType.WINDOWED,
                        true,
                        854.5D,
                        true,
                        100_000.25D),
                base.launcher(),
                base.quickPlay(),
                base.launchOptions(),
                base.jvm(),
                base.commands(),
                base.graphics(),
                base.nativeLibraries());
        RecordingStore store = new RecordingStore(precise);
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(store);
                panelReference.set(panel);
                findNamed(panel, "instanceGameSettingsSave", JButton.class).doClick();
            });

            assertEquals(1, store.saveCount.get());
            assertEquals(854.5D, store.snapshot().window().width());
            assertEquals(100_000.25D, store.snapshot().window().height());
        } finally {
            closePanel(panelReference);
        }
    }

    /// Rejects non-finite and negative local window dimensions before persistence.
    @Test
    void rejectsInvalidWindowDimensions() {
        RecordingStore store = new RecordingStore(snapshot());
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = createPanel(store);
                panelReference.set(panel);
                clickOverride(panel, "instanceGameSettingsWindowWidth");
                JTextField width = findNamed(panel, "instanceGameSettingsWindowWidth", JTextField.class);
                JButton save = findNamed(panel, "instanceGameSettingsSave", JButton.class);
                JLabel status = findNamed(panel, "instanceGameSettingsStatus", JLabel.class);

                for (String invalidValue : List.of("NaN", "Infinity", "-1")) {
                    width.setText(invalidValue);
                    save.doClick();
                    assertEquals(0, store.saveCount.get());
                    assertTrue(status.getText().startsWith(i18n("swing.instance_settings.save_failed", "")));
                }
            });
        } finally {
            closePanel(panelReference);
        }
    }

    /// Creates one writable inherited snapshot with valid effective values.
    ///
    /// @return initial settings snapshot
    private static InstanceGameSettingsSnapshot snapshot() {
        return new InstanceGameSettingsSnapshot(
                true,
                new InstanceGameSettingsSnapshot.ParentPresetSettings(null, List.of()),
                new InstanceGameSettingsSnapshot.MemorySettings(false, true, false, 4096),
                new InstanceGameSettingsSnapshot.JavaRuntimeSettings(
                        false,
                        JavaVersionType.AUTO,
                        false,
                        "",
                        false,
                        "",
                        false,
                        GameSettings.DetectedJava.EMPTY),
                new InstanceGameSettingsSnapshot.WindowSettings(
                        false,
                        GameWindowType.WINDOWED,
                        false,
                        854,
                        false,
                        480),
                new InstanceGameSettingsSnapshot.LauncherSettings(
                        false,
                        LauncherVisibility.HIDE,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false),
                new InstanceGameSettingsSnapshot.QuickPlaySettings(
                        false,
                        QuickPlayType.NONE,
                        false,
                        "",
                        false,
                        "",
                        false,
                        ""),
                new InstanceGameSettingsSnapshot.LaunchOptionsSettings(
                        false,
                        "",
                        false,
                        "",
                        false,
                        "",
                        false,
                        ProcessPriority.NORMAL),
                new InstanceGameSettingsSnapshot.JvmSettings(
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        "",
                        false,
                        null,
                        false,
                        ""),
                new InstanceGameSettingsSnapshot.CommandSettings(false, "", false, "", false, ""),
                new InstanceGameSettingsSnapshot.GraphicsSettings(
                        false,
                        GraphicsAPI.DEFAULT,
                        false,
                        Renderer.DEFAULT,
                        false,
                        Renderer.DEFAULT,
                        false,
                        false),
                new InstanceGameSettingsSnapshot.NativeLibrarySettings(
                        false,
                        false,
                        false,
                        "",
                        false,
                        false,
                        false,
                        false,
                        false,
                        false));
    }

    /// Creates one snapshot with exact whitespace-sensitive free-form values.
    ///
    /// @return settings snapshot carrying local free-form overrides
    private static InstanceGameSettingsSnapshot snapshotWithFreeFormWhitespace() {
        InstanceGameSettingsSnapshot base = snapshot();
        return new InstanceGameSettingsSnapshot(
                base.writable(),
                base.parentPreset(),
                base.memory(),
                base.javaRuntime(),
                base.window(),
                base.launcher(),
                base.quickPlay(),
                new InstanceGameSettingsSnapshot.LaunchOptionsSettings(
                        false,
                        "",
                        true,
                        "  --demo  ",
                        true,
                        "  XYML_TEST=1  ",
                        false,
                        ProcessPriority.NORMAL),
                new InstanceGameSettingsSnapshot.JvmSettings(
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        true,
                        "\n  -XX:+UseG1GC  \n",
                        false,
                        null,
                        false,
                        ""),
                new InstanceGameSettingsSnapshot.CommandSettings(
                        true,
                        "  echo pre  ",
                        true,
                        "  wrapper  ",
                        true,
                        "  echo post  "),
                base.graphics(),
                base.nativeLibraries());
    }

    /// Creates one snapshot with an explicit Java-runtime settings group.
    ///
    /// @param javaRuntime replacement Java-runtime settings
    /// @return complete settings snapshot with the requested Java settings
    private static InstanceGameSettingsSnapshot snapshotWithJavaRuntime(
            InstanceGameSettingsSnapshot.JavaRuntimeSettings javaRuntime) {
        InstanceGameSettingsSnapshot base = snapshot();
        return new InstanceGameSettingsSnapshot(
                base.writable(),
                base.parentPreset(),
                base.memory(),
                Objects.requireNonNull(javaRuntime, "javaRuntime"),
                base.window(),
                base.launcher(),
                base.quickPlay(),
                base.launchOptions(),
                base.jvm(),
                base.commands(),
                base.graphics(),
                base.nativeLibraries());
    }

    /// Creates a snapshot with an explicit effective running directory and inheritance state.
    ///
    /// @param overridden whether the instance owns the running-directory setting
    /// @param runningDirectory effective running-directory text
    /// @return complete settings snapshot with the requested launch option
    private static InstanceGameSettingsSnapshot snapshotWithRunningDirectory(
            boolean overridden,
            String runningDirectory) {
        InstanceGameSettingsSnapshot base = snapshot();
        return new InstanceGameSettingsSnapshot(
                base.writable(),
                base.parentPreset(),
                base.memory(),
                base.javaRuntime(),
                base.window(),
                base.launcher(),
                base.quickPlay(),
                new InstanceGameSettingsSnapshot.LaunchOptionsSettings(
                        overridden,
                        Objects.requireNonNull(runningDirectory, "runningDirectory"),
                        false,
                        "",
                        false,
                        "",
                        false,
                        ProcessPriority.NORMAL),
                base.jvm(),
                base.commands(),
                base.graphics(),
                base.nativeLibraries());
    }

    /// Copies a snapshot with a different parent-preset selection and choice list.
    ///
    /// @param source source snapshot
    /// @param parentPreset replacement parent-preset state
    /// @return copied snapshot
    private static InstanceGameSettingsSnapshot withParentPreset(
            InstanceGameSettingsSnapshot source,
            InstanceGameSettingsSnapshot.ParentPresetSettings parentPreset) {
        return new InstanceGameSettingsSnapshot(
                source.writable(),
                parentPreset,
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
    }

    /// Copies a snapshot with a different effective inherited maximum memory.
    ///
    /// @param source source snapshot
    /// @param maximumMiB replacement effective maximum memory
    /// @return copied snapshot
    private static InstanceGameSettingsSnapshot withMaximumMemory(
            InstanceGameSettingsSnapshot source,
            int maximumMiB) {
        InstanceGameSettingsSnapshot.MemorySettings memory = source.memory();
        return new InstanceGameSettingsSnapshot(
                source.writable(),
                source.parentPreset(),
                new InstanceGameSettingsSnapshot.MemorySettings(
                        memory.automaticOverridden(),
                        memory.automatic(),
                        memory.maximumOverridden(),
                        maximumMiB),
                source.javaRuntime(),
                source.window(),
                source.launcher(),
                source.quickPlay(),
                source.launchOptions(),
                source.jvm(),
                source.commands(),
                source.graphics(),
                source.nativeLibraries());
    }

    /// Copies a snapshot with a different writable state.
    ///
    /// @param source source snapshot
    /// @param writable replacement writable state
    /// @return copied snapshot
    private static InstanceGameSettingsSnapshot withWritable(
            InstanceGameSettingsSnapshot source,
            boolean writable) {
        return new InstanceGameSettingsSnapshot(
                writable,
                source.parentPreset(),
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
    }

    /// Creates a panel isolated from process-wide Java runtime and user-settings state.
    ///
    /// @param store deterministic game-settings store
    /// @return panel backed by an empty uninitialized runtime snapshot
    private static InstanceGameSettingsPanel createPanel(InstanceGameSettingsStore store) {
        return createPanel(store, () -> { });
    }

    /// Creates a panel with an explicit working-directory change callback.
    ///
    /// @param store deterministic game-settings store
    /// @param workingDirectoryChanged callback invoked after a durable working-directory change
    /// @return panel backed by an empty uninitialized runtime snapshot
    private static InstanceGameSettingsPanel createPanel(
            InstanceGameSettingsStore store,
            Runnable workingDirectoryChanged) {
        return new InstanceGameSettingsPanel(
                Objects.requireNonNull(store, "store"),
                new RecordingJavaRuntimeService(new JavaRuntimeManagementSnapshot(false, 0L, List.of())),
                CompletableFuture.completedFuture(GameVersionNumber.unknown()),
                GameSettingsEditorPresentation.INSTANCE,
                Objects.requireNonNull(workingDirectoryChanged, "workingDirectoryChanged"));
    }

    /// Returns every editor name represented by the complete settings snapshot.
    ///
    /// @return immutable stable editor-name list
    private static @Unmodifiable List<String> editorNames() {
        return List.of(
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
                "instanceGameSettingsHighPerformanceGpu",
                "instanceGameSettingsUseCustomNatives",
                "instanceGameSettingsNativesDirectory",
                "instanceGameSettingsDisableNativePatching",
                "instanceGameSettingsUseNativeGlfw",
                "instanceGameSettingsUseNativeOpenAl");
    }

    /// Returns Java payload editors whose applicability is controlled by radio rows instead of override boxes.
    ///
    /// @return immutable Java editor-name list
    private static @Unmodifiable List<String> javaEditorNames() {
        return List.of(
                "instanceGameSettingsJavaVersion",
                "instanceGameSettingsJavaPath",
                "instanceGameSettingsDetectedJava");
    }

    /// Clicks one local-override checkbox.
    ///
    /// @param panel settings panel
    /// @param editorName stable editor name
    private static void clickOverride(InstanceGameSettingsPanel panel, String editorName) {
        findNamed(panel, editorName + "Override", JCheckBox.class).doClick();
    }

    /// Enables one override and writes a text value.
    ///
    /// @param panel settings panel
    /// @param editorName stable editor name
    /// @param value edited text
    private static void overrideText(InstanceGameSettingsPanel panel, String editorName, String value) {
        clickOverride(panel, editorName);
        findNamed(panel, editorName, JTextField.class).setText(value);
    }

    /// Enables one override and selects a boolean value.
    ///
    /// @param panel settings panel
    /// @param editorName stable editor name
    /// @param value desired checkbox value
    private static void overrideBoolean(InstanceGameSettingsPanel panel, String editorName, boolean value) {
        clickOverride(panel, editorName);
        JCheckBox editor = findNamed(panel, editorName, JCheckBox.class);
        if (editor.isSelected() != value) {
            editor.doClick();
        }
    }

    /// Enables the Java-strategy override and selects one highlighted mode.
    ///
    /// @param panel settings panel
    /// @param mode desired Java strategy
    private static void overrideJavaMode(InstanceGameSettingsPanel panel, JavaVersionType mode) {
        findNamed(
                panel,
                "instanceGameSettingsJavaMode" + Objects.requireNonNull(mode, "mode").name(),
                JRadioButton.class).doClick();
    }

    /// Selects one explicit local memory-allocation mode.
    ///
    /// @param panel settings panel
    /// @param automatic whether automatic allocation should be selected
    private static void selectMemoryMode(InstanceGameSettingsPanel panel, boolean automatic) {
        findNamed(
                panel,
                "instanceGameSettingsMemoryMode" + (automatic ? "Automatic" : "Manual"),
                JRadioButton.class).doClick();
    }

    /// Lays out a production scroll tab once at each nesting level, matching one normal validation pass.
    ///
    /// @param scrollPane tab scroll pane
    /// @param width test viewport width
    /// @param height test viewport height
    private static void layoutScrollableTab(JScrollPane scrollPane, int width, int height) {
        JScrollPane validatedPane = Objects.requireNonNull(scrollPane, "scrollPane");
        validatedPane.setSize(width, height);
        validatedPane.doLayout();
        validatedPane.getViewport().doLayout();
        Component view = validatedPane.getViewport().getView();
        if (view instanceof Container container) {
            layoutTree(container);
        }
    }

    /// Recursively lays out one nested Swing component tree.
    ///
    /// @param root container whose descendants need current bounds
    private static void layoutTree(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) {
            if (child instanceof Container container) {
                layoutTree(container);
            }
        }
    }

    /// Returns the empty vertical space between two siblings ordered from top to bottom.
    ///
    /// @param upper component on the preceding row
    /// @param lower component on the following row
    /// @return vertical gap in pixels
    private static int verticalGap(Component upper, Component lower) {
        return lower.getY() - upper.getY() - upper.getHeight();
    }

    /// Asserts one complete label ends before its aligned editor starts.
    ///
    /// @param label localized setting label
    /// @param editor editor aligned in the following column
    private static void assertLabelPrecedesEditor(JLabel label, Component editor) {
        assertSame(label.getParent(), editor.getParent());
        assertTrue(label.getWidth() >= label.getPreferredSize().width);
        assertEquals(16, editor.getX() - label.getX() - label.getWidth());
    }

    /// Asserts an inheritance checkbox remains left of and vertically aligned with its own editor.
    ///
    /// @param override inheritance checkbox
    /// @param editor editor controlled by the checkbox
    private static void assertOverridePrecedesSameRow(JCheckBox override, Component editor) {
        assertSame(override.getParent(), editor.getParent());
        assertTrue(override.getX() + override.getWidth() <= editor.getX());
        assertTrue(override.getY() < editor.getY() + editor.getHeight());
        assertTrue(editor.getY() < override.getY() + override.getHeight());
    }

    /// Asserts three row gaps differ only by normal device-scale rounding.
    ///
    /// @param first first measured gap
    /// @param second second measured gap
    /// @param third third measured gap
    private static void assertUniformRowGaps(int first, int second, int third) {
        int minimum = Math.min(first, Math.min(second, third));
        int maximum = Math.max(first, Math.max(second, third));
        assertTrue(minimum >= 9);
        assertTrue(maximum <= 11);
        assertTrue(maximum - minimum <= 1);
    }

    /// Enables one override and selects a combo value.
    ///
    /// @param panel settings panel
    /// @param editorName stable editor name
    /// @param value desired combo value
    private static void overrideChoice(InstanceGameSettingsPanel panel, String editorName, Object value) {
        clickOverride(panel, editorName);
        findNamed(panel, editorName, JComboBox.class).setSelectedItem(Objects.requireNonNull(value, "value"));
    }

    /// Simulates the user opening one combo without creating a native popup in headless tests.
    ///
    /// @param comboBox combo whose lazy popup listeners should run
    private static void openPopup(JComboBox<?> comboBox) {
        PopupMenuEvent event = new PopupMenuEvent(Objects.requireNonNull(comboBox, "comboBox"));
        for (PopupMenuListener listener : comboBox.getPopupMenuListeners()) {
            listener.popupMenuWillBecomeVisible(event);
        }
    }

    /// Waits until asynchronous detected-Java conversion reaches one stable item count.
    ///
    /// @param panelReference created settings panel
    /// @param expected expected combo item count
    /// @throws InterruptedException if the bounded wait is interrupted
    private static void awaitDetectedJavaItemCount(
            AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference,
            int expected) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        AtomicInteger actual = new AtomicInteger(-1);
        while (System.nanoTime() < deadline) {
            EdtDispatcher.executeAndWait(() -> actual.set(findNamed(
                    Objects.requireNonNull(panelReference.get()),
                    "instanceGameSettingsDetectedJava",
                    JComboBox.class).getItemCount()));
            if (actual.get() == expected) {
                return;
            }
            Thread.sleep(10L);
        }
        assertEquals(expected, actual.get());
    }

    /// Creates one runtime whose existing path yields a distinct persisted identity.
    ///
    /// @param path existing test path
    /// @return runtime using the current JVM metadata
    private static JavaRuntime runtime(Path path) {
        return new JavaRuntime(
                Objects.requireNonNull(path, "path"),
                JavaInfo.CURRENT_ENVIRONMENT,
                false,
                false);
    }

    /// Asserts the three independently version-gated graphics rows.
    ///
    /// @param panel settings panel
    /// @param backendVisible expected backend-row visibility
    /// @param openGlVisible expected OpenGL-row visibility
    /// @param vulkanVisible expected Vulkan-row visibility
    private static void assertGraphicsVisibility(
            InstanceGameSettingsPanel panel,
            boolean backendVisible,
            boolean openGlVisible,
            boolean vulkanVisible) {
        assertEquals(
                backendVisible,
                findNamed(panel, "instanceGameSettingsGraphicsBackendRow", JPanel.class).isVisible());
        assertEquals(
                openGlVisible,
                findNamed(panel, "instanceGameSettingsOpenGlRendererRow", JPanel.class).isVisible());
        assertEquals(
                vulkanVisible,
                findNamed(panel, "instanceGameSettingsVulkanRendererRow", JPanel.class).isVisible());
    }

    /// Closes a panel created during a test.
    ///
    /// @param panelReference panel reference
    private static void closePanel(AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference) {
        @Nullable InstanceGameSettingsPanel panel = panelReference.get();
        if (panel != null) {
            panel.close();
        }
    }

    /// Finds one named descendant of the required Swing component type.
    ///
    /// @param root root component tree
    /// @param name stable component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component
    private static <T extends JComponent> T findNamed(Container root, String name, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findNamedNullable(child, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        throw new AssertionError("Missing component: " + name);
    }

    /// Finds one named descendant or returns `null` while recursion continues.
    ///
    /// @param root root component tree
    /// @param name stable component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component, or `null` when absent
    private static <T extends JComponent> @Nullable T findNamedNullable(
            Container root,
            String name,
            Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findNamedNullable(child, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// In-memory store exposing every persistence request for focused panel assertions.
    @NotNullByDefault
    private static final class RecordingStore implements InstanceGameSettingsStore {
        /// Latest snapshot returned after one save.
        private InstanceGameSettingsSnapshot storedSnapshot;

        /// Number of calls that crossed the UI persistence boundary.
        private final AtomicInteger saveCount = new AtomicInteger();

        /// Number of unsaved parent-preset preview requests.
        private final AtomicInteger previewCount = new AtomicInteger();

        /// Number of confirmed backup-and-overwrite requests.
        private final AtomicInteger forceOverwriteCount = new AtomicInteger();

        /// Preview transformation used by focused parent-preset tests.
        private Function<InstanceGameSettingsSnapshot, InstanceGameSettingsSnapshot> previewer = Function.identity();

        /// Whether the test store exposes read-only recovery.
        private boolean forceOverwriteAvailable;

        /// Forced running directory returned to the editor, or `null` for configurable isolation.
        private final @Nullable String forcedRunningDirectory;

        /// Creates a deterministic store with one initial snapshot.
        ///
        /// @param initialSnapshot initial effective values and override flags
        private RecordingStore(InstanceGameSettingsSnapshot initialSnapshot) {
            this(initialSnapshot, null);
        }

        /// Creates a deterministic store with optional forced modpack isolation.
        ///
        /// @param initialSnapshot initial effective values and override flags
        /// @param forcedRunningDirectory forced instance root, or `null` for configurable isolation
        private RecordingStore(
                InstanceGameSettingsSnapshot initialSnapshot,
                @Nullable String forcedRunningDirectory) {
            storedSnapshot = Objects.requireNonNull(initialSnapshot, "initialSnapshot");
            this.forcedRunningDirectory = forcedRunningDirectory;
        }

        /// Returns the forced test directory when this store represents a modpack.
        ///
        /// @return forced directory, or `null` for normal instances
        @Override
        public @Nullable String forcedRunningDirectory() {
            return forcedRunningDirectory;
        }

        /// Returns the latest recorded snapshot.
        @Override
        public InstanceGameSettingsSnapshot snapshot() {
            return storedSnapshot;
        }

        /// Resolves one unsaved parent-preset candidate through the configured test transformation.
        ///
        /// @param candidate complete unsaved state
        /// @return transformed preview state
        @Override
        public InstanceGameSettingsSnapshot preview(InstanceGameSettingsSnapshot candidate) {
            previewCount.incrementAndGet();
            return previewer.apply(Objects.requireNonNull(candidate, "candidate"));
        }

        /// Records a full instance-settings write.
        ///
        /// @param snapshot validated snapshot from the panel
        @Override
        public void save(InstanceGameSettingsSnapshot snapshot) {
            storedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
            saveCount.incrementAndGet();
        }

        /// Returns whether the focused store permits recovery.
        ///
        /// @return configured recovery availability
        @Override
        public boolean canForceOverwrite() {
            return forceOverwriteAvailable;
        }

        /// Records one confirmed backup-and-overwrite request and makes the snapshot writable.
        @Override
        public void forceOverwrite() {
            if (!forceOverwriteAvailable) {
                throw new IllegalStateException("Recovery is unavailable");
            }
            forceOverwriteCount.incrementAndGet();
            storedSnapshot = withWritable(storedSnapshot, true);
        }
    }

    /// Deterministic local-Java service that exposes refresh and subscription behavior to the panel test.
    @NotNullByDefault
    private static final class RecordingJavaRuntimeService implements JavaRuntimeManagementService {
        /// Latest runtime snapshot returned to the panel.
        private JavaRuntimeManagementSnapshot currentSnapshot;

        /// Single panel listener registered during the test.
        private final AtomicReference<@Nullable ValueChangeListener<JavaRuntimeManagementSnapshot>> listener =
                new AtomicReference<>();

        /// Number of lazy local discovery requests.
        private final AtomicInteger refreshCount = new AtomicInteger();

        /// Creates the service with an explicit uninitialized or initialized snapshot.
        ///
        /// @param initialSnapshot initial runtime state
        private RecordingJavaRuntimeService(JavaRuntimeManagementSnapshot initialSnapshot) {
            currentSnapshot = Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        }

        /// Returns the current test snapshot.
        @Override
        public JavaRuntimeManagementSnapshot snapshot() {
            return currentSnapshot;
        }

        /// Registers the one panel listener used by this focused test.
        ///
        /// @param target snapshot listener
        /// @return removable registration
        @Override
        public Subscription subscribe(ValueChangeListener<JavaRuntimeManagementSnapshot> target) {
            ValueChangeListener<JavaRuntimeManagementSnapshot> required = Objects.requireNonNull(target, "target");
            if (!listener.compareAndSet(null, required)) {
                throw new IllegalStateException("Only one test subscriber is supported");
            }
            return Subscription.create(() -> listener.compareAndSet(required, null));
        }

        /// Records one lazy local scan request.
        @Override
        public void refreshLocalRuntimes() {
            refreshCount.incrementAndGet();
        }

        /// Returns an unused failing runtime-registration task.
        ///
        /// @param selectedPath unused selected path
        /// @return stopped task that fails if unexpectedly started
        @Override
        public Task<JavaRuntime> addLocalRuntime(Path selectedPath) {
            Objects.requireNonNull(selectedPath, "selectedPath");
            return unusedTask();
        }

        /// Returns an unused failing runtime-disable task.
        ///
        /// @param runtime unused unmanaged runtime
        /// @return stopped task that fails if unexpectedly started
        @Override
        public Task<@Nullable Void> disableLocalRuntime(JavaRuntime runtime) {
            Objects.requireNonNull(runtime, "runtime");
            return unusedTask();
        }

        /// Returns an unused failing managed-runtime uninstall task.
        ///
        /// @param runtime unused managed runtime
        /// @return stopped task that fails if unexpectedly started
        @Override
        public Task<@Nullable Void> uninstallManagedRuntime(JavaRuntime runtime) {
            Objects.requireNonNull(runtime, "runtime");
            return unusedTask();
        }

        /// Returns an unused failing disabled-runtime inspection task.
        ///
        /// @param disabledRuntime unused disabled runtime entry
        /// @return stopped task that fails if unexpectedly started
        @Override
        public Task<DisabledJavaRuntimeEntry> inspectDisabledRuntime(DisabledJavaRuntimeEntry disabledRuntime) {
            Objects.requireNonNull(disabledRuntime, "disabledRuntime");
            return unusedTask();
        }

        /// Returns an unused failing disabled-runtime restore task.
        ///
        /// @param disabledRuntime unused disabled runtime entry
        /// @return stopped task that fails if unexpectedly started
        @Override
        public Task<JavaRuntime> restoreDisabledRuntime(DisabledJavaRuntimeEntry disabledRuntime) {
            Objects.requireNonNull(disabledRuntime, "disabledRuntime");
            return unusedTask();
        }

        /// Returns an unused failing disabled-runtime removal task.
        ///
        /// @param disabledRuntime unused disabled runtime entry
        /// @return stopped task that fails if unexpectedly started
        @Override
        public Task<@Nullable Void> removeDisabledRuntime(DisabledJavaRuntimeEntry disabledRuntime) {
            Objects.requireNonNull(disabledRuntime, "disabledRuntime");
            return unusedTask();
        }

        /// Creates a stopped task that exposes unexpected use of an out-of-scope operation.
        ///
        /// @param <T> task result type
        /// @return stopped failing task
        private static <T> Task<T> unusedTask() {
            return Task.supplyAsync(() -> {
                throw new UnsupportedOperationException("Not used by this test");
            });
        }

        /// Publishes one completed discovery result to the registered panel listener.
        ///
        /// @param nextSnapshot next runtime state
        private void publish(JavaRuntimeManagementSnapshot nextSnapshot) {
            JavaRuntimeManagementSnapshot previous = currentSnapshot;
            currentSnapshot = Objects.requireNonNull(nextSnapshot, "nextSnapshot");
            @Nullable ValueChangeListener<JavaRuntimeManagementSnapshot> target = listener.get();
            if (target != null) {
                target.onChange(new ValueChange<>(this, previous, currentSnapshot));
            }
        }

        /// Returns the number of lazy discovery requests.
        ///
        /// @return refresh request count
        private int refreshCount() {
            return refreshCount.get();
        }

        /// Returns whether the panel currently owns the test subscription.
        ///
        /// @return active subscriber count
        private int subscriberCount() {
            return listener.get() == null ? 0 : 1;
        }
    }
}
