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
package space.minecraftstl.xyml.ui.swing.page.instances;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.util.Objects;

/// Cheap searchable identity for one installed instance without icon or version-detail resolution.
///
/// @param stableId stable repository identifier used for loading and commands
/// @param displayName user-visible name matched by the instance-page search
@NotNullByDefault
public record InstanceSearchEntry(GameInstanceID stableId, String displayName) {
    /// Validates one immutable search entry.
    public InstanceSearchEntry {
        Objects.requireNonNull(stableId, "stableId");
        Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("Instance display name cannot be blank");
        }
    }
}
