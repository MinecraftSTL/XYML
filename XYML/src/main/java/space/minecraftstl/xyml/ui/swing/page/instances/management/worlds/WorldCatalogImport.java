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
package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.Objects;

/// A readable local world archive preview ready for a user-confirmed installation name.
///
/// The actual extraction policy is intentionally enforced again by Core at mutation time. This
/// record therefore captures preview metadata, not an authorization to skip final validation.
///
/// @param source normalized archive path selected by the user
/// @param suggestedName non-blank Core-derived world name proposed to the user
@NotNullByDefault
public record WorldCatalogImport(Path source, String suggestedName) {
    /// Normalizes the selected source and rejects an empty target suggestion.
    public WorldCatalogImport {
        source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        suggestedName = Objects.requireNonNull(suggestedName, "suggestedName");
        if (suggestedName.isBlank()) {
            throw new IllegalArgumentException("suggestedName must not be blank");
        }
    }
}
