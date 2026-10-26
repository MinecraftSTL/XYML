// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
