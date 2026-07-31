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
package space.minecraftstl.xyml.ui.swing.page.instances.management.backups;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.Objects;

/// One shallowly indexed direct child of an instance's `saves` directory.
///
/// This value intentionally does not claim that its directory is a readable Minecraft world. The
/// initial index must remain inexpensive, so `World` validation is deferred until the user asks to
/// create a backup from the selected source.
///
/// @param directory normalized direct-child directory in `saves`
/// @param directoryName visible direct-child directory name
@NotNullByDefault
public record WorldBackupSource(Path directory, String directoryName) {
    /// Normalizes and validates one shallow source entry.
    ///
    /// @param directory direct-child directory in the saves folder
    /// @param directoryName visible directory name
    public WorldBackupSource {
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        directoryName = Objects.requireNonNull(directoryName, "directoryName");
        if (directoryName.isBlank()) {
            throw new IllegalArgumentException("directoryName must not be blank");
        }
    }

    /// Returns the concise source label used by standard Swing list renderers.
    ///
    /// @return visible directory name
    @Override
    public String toString() {
        return directoryName;
    }
}
