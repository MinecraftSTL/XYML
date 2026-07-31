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
import space.minecraftstl.xyml.setting.GameSettingsPresetID;

import java.util.Objects;

/// Immutable display and edit state for one game-settings preset.
///
/// `displayName` is localized for the current launcher locale, while `customName` retains the persisted plain text
/// that a rename dialog should edit. `editor` contains the complete directly persisted game-settings surface.
///
/// @param id stable preset identity
/// @param displayName localized user-facing name
/// @param customName persisted custom name, or null for an automatic name
/// @param autoNameNumber automatic-name sequence number, or null when the preset has no automatic name
/// @param defaultPreset whether this preset is the launcher default
/// @param editor complete values accepted by the global preset editor
@NotNullByDefault
public record GameSettingsPresetSnapshot(
        GameSettingsPresetID id,
        String displayName,
        @Nullable String customName,
        @Nullable Integer autoNameNumber,
        boolean defaultPreset,
        GameSettingsPresetEditor editor) {
    /// Validates non-null snapshot components retained by Swing controls.
    public GameSettingsPresetSnapshot {
        id = Objects.requireNonNull(id, "id");
        displayName = Objects.requireNonNull(displayName, "displayName");
        editor = Objects.requireNonNull(editor, "editor");
        if (!id.equals(editor.id())) {
            throw new IllegalArgumentException("snapshot and editor preset identities must match");
        }
    }

    /// Converts this rendered preset into an editor value that can be applied without losing supported fields.
    ///
    /// @return mutable-form values represented by this immutable snapshot
    public GameSettingsPresetEditor toEditor() {
        return editor;
    }
}
