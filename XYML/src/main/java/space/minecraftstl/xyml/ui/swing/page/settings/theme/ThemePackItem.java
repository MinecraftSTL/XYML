// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.theme.ThemeReference;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/// Lightweight searchable metadata for one selectable theme declaration.
///
/// This value never opens or decodes package resources, so viewport rendering and filtering remain safe on the EDT.
///
/// @param reference exact persisted package and theme reference
/// @param displayName localized theme display name
/// @param packageName localized containing package name
/// @param version package version
/// @param authors localized comma-separated authors, or an empty string
/// @param description localized description, or `null`
/// @param builtIn whether the package is embedded and therefore immutable
/// @param installedDirectory exact validated installation directory, or `null` for an embedded package
@NotNullByDefault
public record ThemePackItem(
        ThemeReference reference,
        String displayName,
        String packageName,
        String version,
        String authors,
        @Nullable String description,
        boolean builtIn,
        @Nullable Path installedDirectory) {
    /// Validates identity, display metadata, and origin-specific path state.
    public ThemePackItem {
        Objects.requireNonNull(reference, "reference");
        displayName = requireNonBlank(displayName, "displayName");
        packageName = requireNonBlank(packageName, "packageName");
        version = requireNonBlank(version, "version");
        authors = Objects.requireNonNull(authors, "authors").trim();
        description = description == null || description.isBlank() ? null : description.trim();
        installedDirectory = installedDirectory == null
                ? null
                : installedDirectory.toAbsolutePath().normalize();
        if (builtIn == (installedDirectory != null)) {
            throw new IllegalArgumentException("Exactly installed themes must declare an installation directory");
        }
    }

    /// Returns whether all searchable metadata contains a normalized query.
    ///
    /// @param normalizedQuery lower-case trimmed query
    /// @return whether this item matches the query
    public boolean matches(String normalizedQuery) {
        String query = Objects.requireNonNull(normalizedQuery, "normalizedQuery");
        if (query.isEmpty()) {
            return true;
        }
        return searchableText().contains(query);
    }

    /// Builds a lower-case search index from already loaded manifest metadata.
    ///
    /// @return lightweight search text
    private String searchableText() {
        return String.join("\n",
                        reference.packId(),
                        Objects.toString(reference.themeId(), ""),
                        displayName,
                        packageName,
                        version,
                        authors,
                        Objects.toString(description, ""))
                .toLowerCase(Locale.ROOT);
    }

    /// Requires a compact non-blank display field.
    ///
    /// @param value candidate value
    /// @param name field name
    /// @return trimmed value
    private static String requireNonBlank(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
