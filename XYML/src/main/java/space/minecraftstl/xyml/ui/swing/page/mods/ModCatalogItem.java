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
package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;

import java.nio.file.Path;
import java.util.Objects;

/// Immutable presentation-safe row for one current local Mod file.
///
/// `localKey` is stable when enabling or disabling renames the physical path, while `path` always
/// names the file represented by this exact content revision.
///
/// @param localKey stable local add-on key supplied by `LocalModFile#getFileName()`
/// @param path normalized absolute current file path
/// @param modId logical Mod identifier
/// @param name parsed human-readable Mod name
/// @param description parsed plain-text description
/// @param authors parsed author display text
/// @param version parsed Mod version
/// @param gameVersion parsed target game version
/// @param loaderType detected Mod loader
/// @param fileName exact current disk file name
/// @param logoBase64 immutable encoded archive logo, or `null` when unavailable
/// @param enabled whether the current path lacks the disabled suffix
@NotNullByDefault
public record ModCatalogItem(
        String localKey,
        Path path,
        String modId,
        String name,
        String description,
        String authors,
        String version,
        String gameVersion,
        ModLoaderType loaderType,
        String fileName,
        @Nullable String logoBase64,
        boolean enabled) {
    /// Normalizes stable values and rejects blank identity fields.
    public ModCatalogItem {
        localKey = Objects.requireNonNull(localKey, "localKey");
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        modId = Objects.requireNonNull(modId, "modId");
        name = Objects.requireNonNull(name, "name");
        description = Objects.requireNonNull(description, "description");
        authors = Objects.requireNonNull(authors, "authors");
        version = Objects.requireNonNull(version, "version");
        gameVersion = Objects.requireNonNull(gameVersion, "gameVersion");
        Objects.requireNonNull(loaderType, "loaderType");
        fileName = Objects.requireNonNull(fileName, "fileName");
        if (logoBase64 != null && logoBase64.isBlank()) {
            logoBase64 = null;
        }
        if (localKey.isBlank()) {
            throw new IllegalArgumentException("localKey must not be blank");
        }
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
    }

    /// Creates a row without an embedded logo for compatibility with synthetic catalog sources.
    ///
    /// @param localKey stable local add-on key
    /// @param path normalized absolute current file path
    /// @param modId logical Mod identifier
    /// @param name parsed human-readable Mod name
    /// @param description parsed plain-text description
    /// @param authors parsed author display text
    /// @param version parsed Mod version
    /// @param gameVersion parsed target game version
    /// @param loaderType detected Mod loader
    /// @param fileName exact current disk file name
    /// @param enabled whether the current path lacks the disabled suffix
    public ModCatalogItem(
            String localKey,
            Path path,
            String modId,
            String name,
            String description,
            String authors,
            String version,
            String gameVersion,
            ModLoaderType loaderType,
            String fileName,
            boolean enabled) {
        this(
                localKey,
                path,
                modId,
                name,
                description,
                authors,
                version,
                gameVersion,
                loaderType,
                fileName,
                null,
                enabled);
    }

    /// Returns the non-blank primary row label.
    ///
    /// @return parsed name, logical identifier, or exact file name in fallback order
    public String displayText() {
        if (!name.isBlank()) {
            return name;
        }
        if (!modId.isBlank()) {
            return modId;
        }
        return fileName;
    }
}
