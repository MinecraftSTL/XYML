// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.concurrent.CompletionStage;

/// Refreshes exactly one explicitly selected loader catalog without exposing a UI toolkit.
///
/// Implementations may use Core network tasks, but callers must invoke this only from an explicit
/// refresh action after both a game version and loader kind are selected.
@FunctionalInterface
@NotNullByDefault
public interface GameLoaderCatalogSource {
    /// Starts a refresh for one exact catalog request.
    ///
    /// @param request explicit selected game-version and loader-kind request
    /// @return eventual immutable concrete loader version items
    CompletionStage<@Unmodifiable List<GameLoaderCatalogItem>> refreshAsync(
            GameLoaderCatalogRequest request);
}
