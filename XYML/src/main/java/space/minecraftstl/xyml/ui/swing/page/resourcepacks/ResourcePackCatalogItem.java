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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.Objects;

/// Immutable presentation-safe row for one installed resource pack.
///
/// No decoded image or mutable resource-pack object crosses this boundary. The normalized absolute
/// path is the stable selection key across manager refreshes.
///
/// @param path normalized absolute resource-pack path
/// @param displayName user-facing pack name without its archive extension
/// @param fileName exact file or directory name used by Minecraft
/// @param description complete plain-text pack description, possibly empty or multiline
/// @param compatibility compatibility with the managed Minecraft instance
/// @param enabled whether the instance options currently enable this pack
@NotNullByDefault
public record ResourcePackCatalogItem(
        Path path,
        String displayName,
        String fileName,
        String description,
        ResourcePackCompatibility compatibility,
        boolean enabled) {
    /// Normalizes the stable path and validates all presentation text.
    public ResourcePackCatalogItem {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        displayName = Objects.requireNonNull(displayName, "displayName");
        fileName = Objects.requireNonNull(fileName, "fileName");
        description = Objects.requireNonNull(description, "description");
        Objects.requireNonNull(compatibility, "compatibility");
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
    }

    /// Returns the non-blank label used by the reusable single-choice renderer.
    ///
    /// @return display name, or exact file name when the metadata-derived name is blank
    public String displayText() {
        return displayName.isBlank() ? fileName : displayName;
    }
}
