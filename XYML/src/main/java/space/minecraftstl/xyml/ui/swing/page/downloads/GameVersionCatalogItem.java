// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
