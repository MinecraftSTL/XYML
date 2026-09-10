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
import space.minecraftstl.xyml.addon.mod.LocalModFile;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;
import space.minecraftstl.xyml.addon.mod.ModManager;
import space.minecraftstl.xyml.util.io.CompressingUtils;
import space.minecraftstl.xyml.util.tree.ZipFileTree;

import kala.compress.archivers.zip.ZipArchiveEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Base64;
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
/// @param logoBase64 immutable encoded archive logo, or `null` when unavailable
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
        @Nullable String logoBase64,
        boolean enabled) {
    /// Maximum archive-logo payload retained in one catalog entry.
    private static final int MAX_LOGO_BYTES = 4 * 1024 * 1024;

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
        if (logoBase64 != null && logoBase64.isBlank()) {
            logoBase64 = null;
        }
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
                null,
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
                String.join("\n",
                        file.getFileName(),
                        file.getId(),
                        file.getName(),
                        file.getDescription().toString(),
                        file.getAuthors(),
                        file.getVersion(),
                        file.getGameVersion(),
                        file.getModLoaderType().name(),
                        namePath.toString()),
                readLogoBase64(file),
                !manager.isDisabled(path));
    }

    /// Reads the declared logo from the local archive without exposing mutable bytes to Swing.
    ///
    /// The read is bounded because a malformed archive must not make a catalog refresh allocate
    /// unbounded memory. Any unreadable, missing, or oversized logo falls back to the generic row
    /// icon at presentation time.
    ///
    /// @param file Core local Mod file
    /// @return Base64-encoded logo bytes, or `null` when unavailable
    private static @Nullable String readLogoBase64(LocalModFile file) {
        @Nullable String entryPath = normalizeLogoPath(file.getLogoPath());
        if (entryPath == null) {
            return null;
        }
        try (ZipFileTree tree = CompressingUtils.openZipTree(file.getFile())) {
            @Nullable ZipArchiveEntry entry = tree.getEntry(entryPath);
            if (entry == null || entry.isDirectory()) {
                return null;
            }
            try (InputStream input = tree.getInputStream(entry);
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) {
                        continue;
                    }
                    if (MAX_LOGO_BYTES - total < read) {
                        return null;
                    }
                    output.write(buffer, 0, read);
                    total += read;
                }
                return total == 0
                        ? null
                        : Base64.getEncoder().encodeToString(output.toByteArray());
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    /// Normalizes an archive-relative logo path and rejects traversal segments.
    ///
    /// @param logoPath metadata-provided archive path
    /// @return normalized relative path, or `null` when invalid or blank
    private static @Nullable String normalizeLogoPath(String logoPath) {
        String[] segments = logoPath.replace('\\', '/').split("/");
        StringBuilder normalized = new StringBuilder();
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                return null;
            }
            if (normalized.length() > 0) {
                normalized.append('/');
            }
            normalized.append(segment);
        }
        return normalized.length() == 0 ? null : normalized.toString();
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
                logoBase64,
                enabled);
    }
}
