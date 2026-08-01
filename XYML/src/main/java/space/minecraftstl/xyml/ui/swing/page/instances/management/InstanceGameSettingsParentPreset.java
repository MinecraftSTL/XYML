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
