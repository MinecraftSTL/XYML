// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;

import java.util.Objects;

/// One selectable parent preset for an instance-specific game-settings file.
///
/// A `null` ID represents the launcher's current default preset, matching the legacy JavaFX page behavior.
///
/// @param id preset identity, or `null` for the default-preset fallback
/// @param displayName visible localized option text
@NotNullByDefault
public record InstanceGameSettingsParentPreset(
        @Nullable GameSettingsPresetID id,
        String displayName) {
    /// Validates the visible option label.
    public InstanceGameSettingsParentPreset {
        displayName = Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }

    /// Returns the visible option text for default combo-box rendering.
    @Override
    public String toString() {
        return displayName;
    }
}
