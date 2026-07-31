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
package space.minecraftstl.xyml.theme;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.Objects;

/// One source copied to a normalized entry below `assets/` during export.
///
/// @param source reopenable resource
/// @param entryName normalized archive entry name
@NotNullByDefault
public record ThemePackAsset(ThemePackResource source, String entryName) {
    /// Required prefix for all asset entries.
    private static final String ASSETS_PREFIX = "assets/";

    /// Creates a local-file asset.
    ///
    /// @param source source file
    /// @param entryName destination archive entry
    public ThemePackAsset(Path source, String entryName) {
        this(new ThemePackResource.File(source), entryName);
    }

    /// Validates the source and destination entry.
    public ThemePackAsset {
        Objects.requireNonNull(source, "source");
        entryName = normalizeEntryName(entryName);
    }

    /// Normalizes and validates a portable asset entry name.
    ///
    /// @param entryName candidate entry
    /// @return normalized entry
    /// @throws IllegalArgumentException when the entry escapes or is not below `assets/`
    public static String normalizeEntryName(String entryName) {
        String normalized = Objects.requireNonNull(entryName, "entryName").trim().replace('\\', '/');
        if (normalized.isEmpty()
                || normalized.length() > 1_024
                || normalized.startsWith("/")
                || normalized.matches("^[A-Za-z]:.*")
                || !normalized.startsWith(ASSETS_PREFIX)
                || normalized.endsWith("/")) {
            throw new IllegalArgumentException("Unsafe theme-pack asset entry: " + entryName);
        }
        for (String segment : normalized.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment) || segment.indexOf(':') >= 0) {
                throw new IllegalArgumentException("Unsafe theme-pack asset segment: " + entryName);
            }
            for (int index = 0; index < segment.length(); index++) {
                if (Character.isISOControl(segment.charAt(index))) {
                    throw new IllegalArgumentException("Control character in theme-pack asset entry");
                }
            }
        }
        return normalized;
    }
}
