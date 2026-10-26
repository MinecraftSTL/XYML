// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.util.List;
import java.util.concurrent.CompletionStage;

/// Loads a complete game-version catalog without exposing a UI toolkit.
///
/// Implementations decide their own network or cache strategy and should observe the cooperative
/// cancellation signal before expensive work and before publishing externally visible side effects.
@FunctionalInterface
@NotNullByDefault
public interface GameVersionCatalogSource {
    /// Starts one catalog load.
    ///
    /// @param cancellation cooperative cancellation signal owned by the catalog model
    /// @return eventual immutable catalog in source-defined display order
    CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> load(LoadCancellation cancellation);
}
