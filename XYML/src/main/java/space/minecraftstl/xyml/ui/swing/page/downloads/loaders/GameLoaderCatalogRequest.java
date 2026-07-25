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
package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// One explicit request for concrete versions of one loader against one Minecraft version.
///
/// Constructing this value does no I/O. Its presence is the boundary that permits a source to call
/// [VersionList#refreshAsync(String)].
///
/// @param gameVersion selected non-blank Minecraft version
/// @param kind selected compatible loader catalog
@NotNullByDefault
public record GameLoaderCatalogRequest(String gameVersion, GameLoaderKind kind) {
    /// Normalizes the game version and validates the selected catalog kind.
    public GameLoaderCatalogRequest {
        gameVersion = Objects.requireNonNull(gameVersion, "gameVersion").trim();
        kind = Objects.requireNonNull(kind, "kind");
        if (gameVersion.isEmpty()) {
            throw new IllegalArgumentException("gameVersion must not be blank");
        }
        if (!GameLoaderCompatibilityMatrix.isAvailableForGameVersion(kind, gameVersion)) {
            throw new IllegalArgumentException(
                    "Loader %s is unavailable for Minecraft %s".formatted(kind, gameVersion));
        }
    }
}
