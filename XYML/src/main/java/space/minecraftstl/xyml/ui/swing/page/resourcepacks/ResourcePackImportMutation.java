// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

/// Multi-source import request captured before worker submission.
///
/// @param sources normalized absolute source paths
@NotNullByDefault
record ResourcePackImportMutation(
        @Unmodifiable List<Path> sources) implements ResourcePackCatalogMutationRequest {
    /// Freezes sources and rejects duplicate inputs.
    ResourcePackImportMutation {
        sources = List.copyOf(sources);
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("At least one resource-pack source is required");
        }
        if (new HashSet<>(sources).size() != sources.size()) {
            throw new IllegalArgumentException("Duplicate resource-pack import source");
        }
    }
}
