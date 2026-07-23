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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/// Immutable Minecraft game-version entry used as one viewport choice.
///
/// The non-blank version ID is the stable selection identity. Source implementations must not
/// return two entries with the same ID in one catalog result.
///
/// @param versionId stable non-blank version identifier
/// @param kind version classification
/// @param releaseDate published timestamp when the upstream catalog provides one
@NotNullByDefault
public record GameVersionCatalogItem(
        String versionId,
        GameVersionKind kind,
        Optional<Instant> releaseDate) {
    /// Validates one immutable catalog entry.
    public GameVersionCatalogItem {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(releaseDate, "releaseDate");
        if (versionId.isBlank()) {
            throw new IllegalArgumentException("Version ID cannot be blank");
        }
    }
}
