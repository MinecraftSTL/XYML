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
