// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.Objects;

/// Single-pack persistent enabled-state request.
///
/// @param path normalized stable current-index path
/// @param enabled desired persisted state
@NotNullByDefault
record ResourcePackEnabledMutation(
        Path path,
        boolean enabled) implements ResourcePackCatalogMutationRequest {
    /// Validates the stable target path.
    ResourcePackEnabledMutation {
        Objects.requireNonNull(path, "path");
    }
}
