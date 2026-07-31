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
import space.minecraftstl.xyml.observable.property.ObjectProperty;
import space.minecraftstl.xyml.observable.property.SimpleObjectProperty;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.setting.GameSettingsPresets;
import space.minecraftstl.xyml.setting.LauncherVisibility;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies launcher-visibility mapping between reusable game presets and their Swing store representation.
@NotNullByDefault
public final class LauncherGameSettingsPresetsStoreTest {
    /// Confirms snapshots expose and update the persisted launcher-visibility property and its instance inheritance.
    @Test
    public void snapshotsAndUpdatesLauncherVisibility() {
        GameSettingsPresetID id =
                GameSettingsPresetID.parse("game-settings-preset:123e4567-e89b-12d3-a456-426614174010");
        GameSettings.Preset preset = new GameSettings.Preset(id);
        preset.launcherVisibilityProperty().setValue(LauncherVisibility.KEEP);
        GameSettingsPresets presets = new GameSettingsPresets();
        presets.getPresets().add(preset);
        ObjectProperty<@Nullable GameSettingsPresetID> defaultPreset = new SimpleObjectProperty<>(id);
        LauncherGameSettingsPresetsStore store = new LauncherGameSettingsPresetsStore(presets, defaultPreset);
        try {
            GameSettingsPresetSnapshot initial = store.snapshot().presets().get(0);
            assertEquals(LauncherVisibility.KEEP, initial.launcherVisibility());

            GameSettingsPresetEditor editor = new GameSettingsPresetEditor(
                    initial.id(),
                    initial.autoMemory(),
                    initial.minMemoryMiB(),
                    initial.maxMemoryMiB(),
                    initial.javaType(),
                    initial.customJavaVersion(),
                    initial.customJavaPath(),
                    initial.jvmOptions(),
                    initial.noJvmOptions(),
                    LauncherVisibility.HIDE_AND_REOPEN,
                    initial.defaultIsolationType());
            GameSettingsPresetsSnapshot updated = onEventDispatchThread(
                    () -> completed(store.updatePreset(editor)));

            assertEquals(LauncherVisibility.HIDE_AND_REOPEN, preset.launcherVisibilityProperty().getValue());
            assertEquals(LauncherVisibility.HIDE_AND_REOPEN, updated.presets().get(0).launcherVisibility());

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

    /// Confirms a newly created preset retains the core launcher's default hide behavior.
    @Test
    public void newPresetUsesCoreLauncherVisibilityDefault() {
        GameSettingsPresets presets = new GameSettingsPresets();
        ObjectProperty<@Nullable GameSettingsPresetID> defaultPreset = new SimpleObjectProperty<>();
        LauncherGameSettingsPresetsStore store = new LauncherGameSettingsPresetsStore(presets, defaultPreset);
        try {
            GameSettingsPresetsSnapshot created = onEventDispatchThread(
                    () -> completed(store.createPreset("Fresh")));

            assertEquals(LauncherVisibility.HIDE, created.presets().get(0).launcherVisibility());
            assertEquals(
                    LauncherVisibility.HIDE,
                    presets.getPresets().get(0).launcherVisibilityProperty().getValue());
        } finally {
            store.close();
        }
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
