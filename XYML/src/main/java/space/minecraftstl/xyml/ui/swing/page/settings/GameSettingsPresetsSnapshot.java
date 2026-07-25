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
