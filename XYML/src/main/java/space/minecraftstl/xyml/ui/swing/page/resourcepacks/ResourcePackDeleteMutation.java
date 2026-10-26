// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.util.io.DeletionMode;

import java.nio.file.Path;
import java.util.Objects;

/// Single-pack persistent disable-then-delete request.
///
/// @param path normalized stable current-index path
/// @param mode selected deletion behavior
@NotNullByDefault
record ResourcePackDeleteMutation(Path path, DeletionMode mode) implements ResourcePackCatalogMutationRequest {
    /// Creates a permanent-delete request for compatibility callers.
    ///
    /// @param path normalized stable current-index path
    ResourcePackDeleteMutation(Path path) {
        this(path, DeletionMode.PERMANENT);
    }

    /// Validates the stable target path and mode.
    ResourcePackDeleteMutation {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");
    }
}
