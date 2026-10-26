// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.download.ComponentRemoteVersion;

import java.util.Objects;

/// Pairs a selected loader kind with its exact Core remote version instance.
///
/// The concrete [ComponentRemoteVersion] is intentionally retained rather than converted into a display-only
/// DTO, so a later installer task can use the original subtype and metadata without re-querying.
///
/// @param kind loader catalog that produced the version
/// @param remoteVersion original Core remote version object
@NotNullByDefault
public record GameLoaderCatalogItem(GameLoaderKind kind, ComponentRemoteVersion remoteVersion) {
    /// Validates source provenance while preserving remote-version identity.
    public GameLoaderCatalogItem {
        kind = Objects.requireNonNull(kind, "kind");
        remoteVersion = Objects.requireNonNull(remoteVersion, "remoteVersion");
    }

    /// Returns concise display text without losing the original remote version.
    ///
    /// @return stable loader name and remote self version
    public String displayText() {
        return kind.displayName() + " " + remoteVersion.getSelfVersion();
    }
}
