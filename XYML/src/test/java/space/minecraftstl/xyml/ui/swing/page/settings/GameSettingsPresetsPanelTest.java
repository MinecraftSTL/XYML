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
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.setting.DefaultIsolationType;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.setting.LauncherVisibility;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Tests selection, global-preset editing, and default-preset commands in the Swing settings page.
@NotNullByDefault
public final class GameSettingsPresetsPanelTest {
    /// Edits a selected preset and makes it the default through the store contract.
    @Test
    public void savesSelectedPresetAndChangesDefault() {
        GameSettingsPresetSnapshot first = preset("1", "Default", true);
        GameSettingsPresetSnapshot second = preset("2", "Performance", false);
        FakeGameSettingsPresetsStore store = new FakeGameSettingsPresetsStore(snapshot(1L, first, second));
        GameSettingsPresetsPanel panel = onEventDispatchThread(() -> new GameSettingsPresetsPanel(store));

        onEventDispatchThread(() -> {
            JList<?> presets = findComponent(panel, "gameSettingsPresetList", JList.class);
            presets.setSelectedIndex(1);
            findComponent(panel, "gameSettingsPresetDefault", AbstractButton.class).doClick();

            findComponent(panel, "gameSettingsPresetAutoMemory", JCheckBox.class).doClick();
            findComponent(panel, "gameSettingsPresetMinMemory", JTextField.class).setText("1024");
            findComponent(panel, "gameSettingsPresetMaxMemory", JTextField.class).setText("6144");
            findComponent(panel, "gameSettingsPresetJavaType", JComboBox.class)
                    .setSelectedItem(JavaVersionType.CUSTOM);
            findComponent(panel, "gameSettingsPresetJavaPath", JTextField.class)
                    .setText("C:/java/21/bin/java.exe");
            findComponent(panel, "gameSettingsPresetJvmOptions", JTextArea.class)
                    .setText("-XX:+UseG1GC");
            findComponent(panel, "gameSettingsPresetNoJvmOptions", JCheckBox.class).setSelected(true);
            JComboBox<?> launcherVisibility =
                    findComponent(panel, "gameSettingsPresetLauncherVisibility", JComboBox.class);
            assertAll(
                    () -> assertEquals(4, launcherVisibility.getItemCount()),
                    () -> assertEquals(LauncherVisibility.CLOSE, launcherVisibility.getItemAt(0)),
                    () -> assertEquals(LauncherVisibility.HIDE, launcherVisibility.getItemAt(1)),
                    () -> assertEquals(LauncherVisibility.KEEP, launcherVisibility.getItemAt(2)),
                    () -> assertEquals(LauncherVisibility.HIDE_AND_REOPEN, launcherVisibility.getItemAt(3)));
            launcherVisibility.setSelectedItem(LauncherVisibility.HIDE_AND_REOPEN);
            findComponent(panel, "gameSettingsPresetIsolation", JComboBox.class)
                    .setSelectedItem(DefaultIsolationType.NEVER);
            findComponent(panel, "gameSettingsPresetSave", AbstractButton.class).doClick();

            @Nullable GameSettingsPresetEditor saved = store.lastEditor.get();
            assertNotNull(saved);
            assertAll(
                    () -> assertEquals(second.id(), store.defaultPreset.get()),
                    () -> assertEquals(second.id(), saved.id()),
                    () -> assertEquals(false, saved.autoMemory()),
                    () -> assertEquals(1024, saved.minMemoryMiB()),
                    () -> assertEquals(6144, saved.maxMemoryMiB()),
                    () -> assertEquals(JavaVersionType.CUSTOM, saved.javaType()),
                    () -> assertEquals("C:/java/21/bin/java.exe", saved.customJavaPath()),
                    () -> assertEquals("-XX:+UseG1GC", saved.jvmOptions()),
                    () -> assertEquals(true, saved.noJvmOptions()),
                    () -> assertEquals(LauncherVisibility.HIDE_AND_REOPEN, saved.launcherVisibility()),
                    () -> assertEquals(DefaultIsolationType.NEVER, saved.defaultIsolationType()));
            panel.close();
        });
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

    /// Creates one reusable preset fixture.
    ///
    /// @param suffix deterministic UUID suffix
    /// @param name visible preset name
    /// @param defaultPreset whether the fixture is initially default
    /// @return immutable preset fixture
    private static GameSettingsPresetSnapshot preset(String suffix, String name, boolean defaultPreset) {
        return new GameSettingsPresetSnapshot(
                GameSettingsPresetID.parse("game-settings-preset:123e4567-e89b-12d3-a456-42661417400" + suffix),
                name,
                name,
                null,
                defaultPreset,
                true,
                null,
                4096,
                JavaVersionType.AUTO,
                "",
                "",
                "",
                false,
                LauncherVisibility.HIDE,
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

        /// Creates a fake store with one initial immutable snapshot.
        ///
        /// @param snapshot starting state
        private FakeGameSettingsPresetsStore(GameSettingsPresetsSnapshot snapshot) {
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
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

        /// Captures editor values and publishes a snapshot that reflects their persisted fields.
        ///
        /// @param editor values saved by the panel
        /// @return completed updated snapshot stage
        @Override
        public CompletionStage<GameSettingsPresetsSnapshot> updatePreset(GameSettingsPresetEditor editor) {
            GameSettingsPresetEditor values = Objects.requireNonNull(editor, "editor");
            lastEditor.set(values);
            publish(replacePreset(values.id(), values, false));
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

        /// Replaces one preset entry while retaining all unrelated list state.
        ///
        /// @param id target preset ID
        /// @param editor replacement editor values, or null when only changing default state
        /// @param makeDefault whether the target should become default
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

        /// Maps one existing entry to its optional editor and default-selection replacement.
        ///
        /// @param preset source entry
        /// @param selectedId selected preset ID
        /// @param editor editor values, or null
        /// @param makeDefault whether selected ID becomes default
        /// @return mapped entry
        private static GameSettingsPresetSnapshot replacementEntry(
                GameSettingsPresetSnapshot preset,
                GameSettingsPresetID selectedId,
                @Nullable GameSettingsPresetEditor editor,
                boolean makeDefault) {
            boolean selected = preset.id().equals(selectedId);
            if (!selected || editor == null) {
                return new GameSettingsPresetSnapshot(
                        preset.id(),
                        preset.displayName(),
                        preset.customName(),
                        preset.autoNameNumber(),
                        makeDefault ? selected : preset.defaultPreset(),
                        preset.autoMemory(),
                        preset.minMemoryMiB(),
                        preset.maxMemoryMiB(),
                        preset.javaType(),
                        preset.customJavaVersion(),
                        preset.customJavaPath(),
                        preset.jvmOptions(),
                        preset.noJvmOptions(),
                        preset.launcherVisibility(),
                        preset.defaultIsolationType());
            }
            return new GameSettingsPresetSnapshot(
                    preset.id(),
                    preset.displayName(),
                    preset.customName(),
                    preset.autoNameNumber(),
                    makeDefault || preset.defaultPreset(),
                    editor.autoMemory(),
                    editor.minMemoryMiB(),
                    editor.maxMemoryMiB(),
                    editor.javaType(),
                    editor.customJavaVersion(),
                    editor.customJavaPath(),
                    editor.jvmOptions(),
                    editor.noJvmOptions(),
                    editor.launcherVisibility(),
                    editor.defaultIsolationType());
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
