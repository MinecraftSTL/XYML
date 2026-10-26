// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;

import java.util.concurrent.CompletionStage;

/// Provides asynchronous presentation commands for reusable game-settings presets.
///
/// Commands may complete immediately when the established settings model can apply an in-memory change directly, but
/// callers must always use the returned stage so a later persistence implementation can move work off the Swing EDT.
@NotNullByDefault
public interface GameSettingsPresetsStore extends AutoCloseable {
    /// Returns the latest immutable state without initiating any file-system operation.
    ///
    /// @return current preset presentation state
    GameSettingsPresetsSnapshot snapshot();

    /// Registers for preset state transitions.
    ///
    /// @param listener listener receiving immutable state changes
    /// @return independently removable listener registration
    Subscription subscribe(ValueChangeListener<GameSettingsPresetsSnapshot> listener);

    /// Creates a preset using an automatic name when `name` is blank.
    ///
    /// @param name optional custom user-facing name
    /// @return completion with the resulting preset state
    CompletionStage<GameSettingsPresetsSnapshot> createPreset(String name);

    /// Renames one existing preset.
    ///
    /// @param id preset to rename
    /// @param name non-blank custom name
    /// @return completion with the resulting preset state
    CompletionStage<GameSettingsPresetsSnapshot> renamePreset(GameSettingsPresetID id, String name);

    /// Deletes one preset while preserving at least one preset and a valid default preset selection.
    ///
    /// @param id preset to delete
    /// @return completion with the resulting preset state
    CompletionStage<GameSettingsPresetsSnapshot> deletePreset(GameSettingsPresetID id);

    /// Makes one existing preset the launcher default.
    ///
    /// @param id preset to use for new and fallback instance settings
    /// @return completion with the resulting preset state
    CompletionStage<GameSettingsPresetsSnapshot> setDefaultPreset(GameSettingsPresetID id);

    /// Applies supported global game-setting values to one preset.
    ///
    /// @param editor validated editor values
    /// @return completion with the resulting preset state
    CompletionStage<GameSettingsPresetsSnapshot> updatePreset(GameSettingsPresetEditor editor);

    /// Releases listeners owned by this store.
    @Override
    void close();
}
