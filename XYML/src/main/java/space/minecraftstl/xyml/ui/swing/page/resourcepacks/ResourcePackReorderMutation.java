// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.Objects;

/// Reorders one enabled resource pack within the persisted Minecraft priority list.
///
/// @param path normalized stable current-index path
/// @param targetIndex final zero-based index in display order, where the first entry has highest priority
@NotNullByDefault
record ResourcePackReorderMutation(
        Path path,
        int targetIndex) implements ResourcePackCatalogMutationRequest {
    /// Validates one stable target path and non-negative display index.
    ResourcePackReorderMutation {
        Objects.requireNonNull(path, "path");
        if (targetIndex < 0) {
            throw new IllegalArgumentException("targetIndex must not be negative");
        }
    }
}
