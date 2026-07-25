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
import space.minecraftstl.xyml.addon.mod.LocalModFile;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;
import space.minecraftstl.xyml.addon.mod.ModManager;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/// Immutable internal index entry captured from one `LocalModFile` on a background thread.
///
/// @param localKey rename-stable local add-on key
/// @param path normalized current path
/// @param modId logical Mod identifier
/// @param name parsed Mod name
/// @param description parsed plain-text description
/// @param authors parsed authors
/// @param version parsed Mod version
/// @param gameVersion parsed target game version
/// @param loaderType detected loader type
/// @param fileName exact current file name
/// @param searchText precomputed normalized metadata search text
/// @param enabled actual suffix-derived state
@NotNullByDefault
record ModCatalogEntry(
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
        String searchText,
        boolean enabled) {
    /// Normalizes one captured index entry.
    ModCatalogEntry {
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
        searchText = Objects.requireNonNull(searchText, "searchText").toLowerCase(Locale.ROOT);
    }

    /// Creates an entry while precomputing normalized metadata search text once.
    ///
    /// @param localKey rename-stable local add-on key
    /// @param path normalized current path
    /// @param modId logical Mod identifier
    /// @param name parsed Mod name
    /// @param description parsed description
    /// @param authors parsed authors
    /// @param version parsed Mod version
    /// @param gameVersion parsed target game version
    /// @param loaderType detected loader
    /// @param fileName exact current file name
    /// @param enabled actual suffix-derived state
    ModCatalogEntry(
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
                String.join("\n",
                        localKey,
                        modId,
                        name,
                        description,
                        authors,
                        version,
                        gameVersion,
                        loaderType.name(),
                        fileName),
                enabled);
    }

    /// Captures immutable metadata and actual on-disk state from a Core Mod file.
    ///
    /// @param file Core local Mod file
    /// @param manager owning manager used for suffix semantics
    /// @return immutable internal index entry
    static ModCatalogEntry from(LocalModFile file, ModManager manager) {
        Path path = file.getFile().toAbsolutePath().normalize();
        Path namePath = Objects.requireNonNull(path.getFileName(), "Mod file must have a file name");
        return new ModCatalogEntry(
                file.getFileName(),
                path,
                file.getId(),
                file.getName(),
                file.getDescription().toString(),
                file.getAuthors(),
                file.getVersion(),
                file.getGameVersion(),
                file.getModLoaderType(),
                namePath.toString(),
                !manager.isDisabled(path));
    }

    /// Returns whether this entry satisfies one normalized query and enabled-state filter.
    ///
    /// @param normalizedQuery lower-case trimmed query
    /// @param filter enabled-state filter
    /// @return whether this entry belongs to the filtered index
    boolean matches(String normalizedQuery, ModCatalogFilter filter) {
        if (!filter.matches(enabled)) {
            return false;
        }
        if (normalizedQuery.isEmpty()) {
            return true;
        }
        return searchText.contains(normalizedQuery);
    }

    /// Materializes the public row only when the measured viewport requests this entry.
    ///
    /// @return presentation-safe row
    ModCatalogItem toItem() {
        return new ModCatalogItem(
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
                enabled);
    }
}
