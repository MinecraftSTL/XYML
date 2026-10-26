// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
