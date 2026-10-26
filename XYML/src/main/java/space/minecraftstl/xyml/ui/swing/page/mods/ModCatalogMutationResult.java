// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Actual post-mutation index and an optional mutation failure.
///
/// @param index complete refreshed index even after a mutation error
/// @param mutationFailure mutation failure, or `null` after success
@NotNullByDefault
record ModCatalogMutationResult(
        ModCatalogIndex index,
        @Nullable Throwable mutationFailure) {
}
