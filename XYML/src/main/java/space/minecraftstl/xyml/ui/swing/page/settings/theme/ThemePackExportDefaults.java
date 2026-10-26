// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Suggested metadata presented before exporting the current launcher theme.
///
/// @param packId generated stable package identifier that is not user-facing
/// @param name suggested package and theme name
/// @param version suggested package version
/// @param author suggested author name
@NotNullByDefault
public record ThemePackExportDefaults(
        String packId,
        String name,
        String version,
        String author) {
    /// Trims and validates every suggested metadata value.
    public ThemePackExportDefaults {
        packId = requireNonBlank(packId, "packId");
        name = requireNonBlank(name, "name");
        version = requireNonBlank(version, "version");
        author = requireNonBlank(author, "author");
    }

    /// Trims one required default value.
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
