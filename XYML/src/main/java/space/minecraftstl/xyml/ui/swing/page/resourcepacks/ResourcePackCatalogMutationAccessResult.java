// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Result of a mutation whose mandatory shallow index refresh succeeded.
///
/// @param refreshedIndex exact post-write shallow index
/// @param mutationFailure mutation failure, or null after success
@NotNullByDefault
record ResourcePackCatalogMutationAccessResult(
        ResourcePackCatalogIndex refreshedIndex,
        @Nullable Throwable mutationFailure) {
    /// Validates the mandatory refreshed index.
    ResourcePackCatalogMutationAccessResult {
        Objects.requireNonNull(refreshedIndex, "refreshedIndex");
    }
}
