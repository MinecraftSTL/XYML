// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.Objects;

/// User-confirmed current-theme metadata and its output target.
///
/// @param packId generated stable package identifier
/// @param name package and theme display name
/// @param version package version
/// @param author package author
/// @param outputFile requested `.xyml-theme` target
@NotNullByDefault
public record ThemePackExportRequest(
        String packId,
        String name,
        String version,
        String author,
        Path outputFile) {
    /// Trims metadata and normalizes the output path without touching the filesystem.
    public ThemePackExportRequest {
        packId = requireNonBlank(packId, "packId");
        name = requireNonBlank(name, "name");
        version = requireNonBlank(version, "version");
        author = requireNonBlank(author, "author");
        outputFile = Objects.requireNonNull(outputFile, "outputFile").toAbsolutePath().normalize();
    }

    /// Trims one required export value.
    ///
    /// @param value candidate value
    /// @param field diagnostic field name
    /// @return non-empty trimmed value
    private static String requireNonBlank(String value, String field) {
        String checked = Objects.requireNonNull(value, field).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return checked;
    }
}
