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
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.util.PortablePath;

import java.util.Objects;

/// Immutable rendered metadata for one effective game-directory entry.
///
/// A relative path belongs to the workspace-local game-directory store and an absolute path belongs to the
/// user-level store. The entry deliberately keeps the persisted [PortablePath] rather than a resolved filesystem path,
/// so rendering the list performs no filesystem I/O.
///
/// @param id stable persisted game-directory identifier
/// @param displayName localized display name resolved for the current launcher locale
/// @param path persisted local or user-level game-directory path
/// @param selected whether this is the process-wide selected game directory
@NotNullByDefault
public record GameDirectoryManagementEntry(
        GameDirectoryID id,
        String displayName,
        PortablePath path,
        boolean selected) {
    /// Validates immutable entry values.
    public GameDirectoryManagementEntry {
        id = Objects.requireNonNull(id, "id");
        displayName = requireNonBlank(displayName, "displayName");
        path = Objects.requireNonNull(path, "path");
    }

    /// Returns whether this entry is persisted in the user-level game-directory store.
    ///
    /// @return `true` for absolute paths and user-level storage
    public boolean isUserDirectory() {
        return path.isAbsolute();
    }

    /// Validates one required visible text value.
    ///
    /// @param value source text
    /// @param name parameter name
    /// @return validated text
    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
