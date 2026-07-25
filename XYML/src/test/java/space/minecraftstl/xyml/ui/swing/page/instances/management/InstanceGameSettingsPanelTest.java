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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies instance launch-settings edits remain explicit until users save valid local overrides.
@NotNullByDefault
final class InstanceGameSettingsPanelTest {
    /// Saves memory, Java, JVM, and game-directory choices through the store as local overrides.
    @Test
    void savesExplicitLocalOverrides() {
        RecordingStore store = new RecordingStore(snapshot());
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = new InstanceGameSettingsPanel(store);
                panelReference.set(panel);

                JCheckBox memoryOverride = findNamed(panel, "instanceGameSettingsMemoryOverride", JCheckBox.class);
                JCheckBox automaticMemory = findNamed(panel, "instanceGameSettingsAutomaticMemory", JCheckBox.class);
                JTextField maximumMemory = findNamed(panel, "instanceGameSettingsMaximumMemory", JTextField.class);
                JCheckBox javaOverride = findNamed(panel, "instanceGameSettingsJavaOverride", JCheckBox.class);
                JComboBox<?> javaMode = findNamed(panel, "instanceGameSettingsJavaMode", JComboBox.class);
                JTextField javaVersion = findNamed(panel, "instanceGameSettingsJavaVersion", JTextField.class);
                JCheckBox jvmOverride = findNamed(panel, "instanceGameSettingsJvmOverride", JCheckBox.class);
                JTextArea jvmOptions = findNamed(panel, "instanceGameSettingsJvmOptions", JTextArea.class);
                JCheckBox directoryOverride = findNamed(
                        panel,
                        "instanceGameSettingsRunningDirectoryOverride",
                        JCheckBox.class);
                JTextField runningDirectory = findNamed(
                        panel,
                        "instanceGameSettingsRunningDirectoryPath",
                        JTextField.class);
                JButton save = findNamed(panel, "instanceGameSettingsSave", JButton.class);
                assertNotNull(memoryOverride);
                assertNotNull(automaticMemory);
                assertNotNull(maximumMemory);
                assertNotNull(javaOverride);
                assertNotNull(javaMode);
                assertNotNull(javaVersion);
                assertNotNull(jvmOverride);
                assertNotNull(jvmOptions);
                assertNotNull(directoryOverride);
                assertNotNull(runningDirectory);
                assertNotNull(save);

                memoryOverride.doClick();
                automaticMemory.doClick();
                maximumMemory.setText("6144");
                javaOverride.doClick();
                javaMode.setSelectedItem(JavaVersionType.VERSION);
                javaVersion.setText("21");
                jvmOverride.doClick();
                jvmOptions.setText("-XX:+UseG1GC");
                directoryOverride.doClick();
                runningDirectory.setText("instance-run");
                save.doClick();
            });

            assertEquals(1, store.saveCount.get());
            InstanceGameSettingsSnapshot saved = store.snapshot();
            assertTrue(saved.memoryOverridden());
            assertFalse(saved.automaticMemory());
            assertEquals(6144, saved.maximumMemoryMiB());
            assertTrue(saved.javaOverridden());
            assertEquals(JavaVersionType.VERSION, saved.javaVersionType());
            assertEquals("21", saved.customJavaVersion());
            assertTrue(saved.jvmOptionsOverridden());
            assertEquals("-XX:+UseG1GC", saved.jvmOptions());
            assertTrue(saved.runningDirectoryOverridden());
            assertEquals("instance-run", saved.runningDirectory());
        } finally {
            @Nullable InstanceGameSettingsPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
        }
    }

    /// Rejects an invalid maximum-memory input before the backing store can persist a partial change.
    @Test
    void rejectsInvalidMemoryBeforeSave() {
        RecordingStore store = new RecordingStore(snapshot());
        AtomicReference<@Nullable InstanceGameSettingsPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceGameSettingsPanel panel = new InstanceGameSettingsPanel(store);
                panelReference.set(panel);
                JTextField maximumMemory = findNamed(panel, "instanceGameSettingsMaximumMemory", JTextField.class);
                JButton save = findNamed(panel, "instanceGameSettingsSave", JButton.class);
                JLabel status = findNamed(panel, "instanceGameSettingsStatus", JLabel.class);
                assertNotNull(maximumMemory);
                assertNotNull(save);
                assertNotNull(status);

                maximumMemory.setText("not-a-number");
                save.doClick();

                assertTrue(status.getText().startsWith("Cannot save:"));
            });
            assertEquals(0, store.saveCount.get());
        } finally {
            @Nullable InstanceGameSettingsPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
        }
    }

    /// Creates one writable inherited snapshot with valid effective values.
    ///
    /// @return initial settings snapshot
    private static InstanceGameSettingsSnapshot snapshot() {
        return new InstanceGameSettingsSnapshot(
                true,
                false,
                true,
                4096,
                false,
                JavaVersionType.AUTO,
                "",
                "",
                false,
                false,
                "",
                false,
                "");
    }

    /// Finds one named descendant of the required Swing component type.
    ///
    /// @param root root component tree
    /// @param name stable component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component, or `null` when absent
    private static <T extends JComponent> @Nullable T findNamed(
            Container root,
            String name,
            Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findNamed(child, name, type);
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
        /// Latest snapshot returned to the panel after one save.
        private InstanceGameSettingsSnapshot storedSnapshot;

        /// Number of calls that crossed the UI persistence boundary.
        private final AtomicInteger saveCount = new AtomicInteger();

        /// Creates a deterministic store with one initial snapshot.
        ///
        /// @param initialSnapshot initial effective values and override flags
        private RecordingStore(InstanceGameSettingsSnapshot initialSnapshot) {
            storedSnapshot = Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        }

        /// Returns the latest recorded snapshot.
        ///
        /// @return current snapshot
        @Override
        public InstanceGameSettingsSnapshot snapshot() {
            return storedSnapshot;
        }

        /// Records a full instance-settings write.
        ///
        /// @param snapshot validated snapshot from the panel
        @Override
        public void save(InstanceGameSettingsSnapshot snapshot) {
            storedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
            saveCount.incrementAndGet();
        }
    }
}
