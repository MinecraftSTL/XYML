// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
