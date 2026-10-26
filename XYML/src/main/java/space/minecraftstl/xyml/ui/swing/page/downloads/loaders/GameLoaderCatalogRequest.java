// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
