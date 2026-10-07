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
package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/// Immutable presentation-safe row for one installed shader pack.
///
/// @param path normalized absolute shader-pack path
/// @param fileName exact direct-child file or directory name
/// @param displayName user-facing name without a ZIP extension
/// @param valid whether the pack contains a recognizable shaders payload
/// @param enabledBackends backends currently selecting this exact pack
/// @param description local package description, or an empty string when unavailable
@NotNullByDefault
public record ShaderPackCatalogItem(
        Path path,
        String fileName,
        String displayName,
        boolean valid,
        @Unmodifiable Set<ShaderPackBackend> enabledBackends,
        String description) {
    /// Normalizes the path and freezes all presentation values.
    public ShaderPackCatalogItem {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        fileName = Objects.requireNonNull(fileName, "fileName");
        displayName = Objects.requireNonNull(displayName, "displayName");
        enabledBackends = Set.copyOf(Objects.requireNonNull(enabledBackends, "enabledBackends"));
        description = Objects.requireNonNull(description, "description").trim();
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
    }

    /// Creates a row without optional package presentation metadata.
    public ShaderPackCatalogItem(
            Path path,
            String fileName,
            String displayName,
            boolean valid,
            @Unmodifiable Set<ShaderPackBackend> enabledBackends) {
        this(path, fileName, displayName, valid, enabledBackends, "");
    }

    /// Returns whether at least one backend selects this pack.
    ///
    /// @return true when the pack is selected by any backend
    public boolean enabled() {
        return !enabledBackends.isEmpty();
    }

    /// Returns the non-blank label used by the list renderer.
    ///
    /// @return display name, or the exact file name when metadata is unavailable
    public String displayText() {
        return displayName.isBlank() ? fileName : displayName;
    }
}
