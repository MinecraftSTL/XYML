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
import space.minecraftstl.xyml.util.PortablePath;

import java.util.Objects;

/// Validated display metadata and persisted path for a newly added or edited game directory.
///
/// @param displayName non-blank user-visible custom name
/// @param path local or user-level portable path selected by the user
@NotNullByDefault
public record GameDirectoryManagementEdit(String displayName, PortablePath path) {
    /// Validates the editable values before the launcher directory manager is mutated.
    public GameDirectoryManagementEdit {
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        path = Objects.requireNonNull(path, "path");
    }
}
