// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;

import java.util.List;
import java.util.Objects;

/// Immutable state rendered by [GameSettingsPresetsPanel].
///
/// @param revision monotonic presentation revision
/// @param writable whether `config/game-settings.json` can be changed without a force-overwrite operation
/// @param presets ordered reusable game-settings presets
@NotNullByDefault
public record GameSettingsPresetsSnapshot(
        long revision,
        boolean writable,
        @Unmodifiable List<GameSettingsPresetSnapshot> presets) {
    /// Validates the revision and defensively copies all preset entries.
    public GameSettingsPresetsSnapshot {
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        presets = List.copyOf(Objects.requireNonNull(presets, "presets"));
    }

    /// Finds one rendered preset by its stable identity.
    ///
    /// @param id desired preset identity, or null when no selection exists
    /// @return matching preset, or null when no matching preset remains
    public @Nullable GameSettingsPresetSnapshot findPreset(@Nullable GameSettingsPresetID id) {
        if (id == null) {
            return null;
        }
        for (GameSettingsPresetSnapshot preset : presets) {
            if (id.equals(preset.id())) {
                return preset;
            }
        }
        return null;
    }
}
