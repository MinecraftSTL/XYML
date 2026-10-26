// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/// Blocking Core access required by the asynchronous Mod catalog model.
@NotNullByDefault
interface ModCatalogAccess {
    /// Returns the normalized managed Mod directory without touching disk.
    ///
    /// @return managed directory
    Path modsDirectory();

    /// Performs a complete real `ModManager` refresh.
    ///
    /// @param cancellation cooperative pre-refresh cancellation
    /// @return complete immutable current index
    /// @throws IOException when local Mod access fails
    ModCatalogIndex refresh(LoadCancellation cancellation) throws IOException;

    /// Materializes only the exact viewport entries in their supplied order.
    ///
    /// @param entries exact immutable viewport slice
    /// @param cancellation cooperative range cancellation
    /// @return one public row per supplied entry
    @Unmodifiable List<ModCatalogItem> loadItems(
            @Unmodifiable List<ModCatalogEntry> entries,
            LoadCancellation cancellation);

    /// Applies one serialized mutation and performs a mandatory full refresh afterward.
    ///
    /// @param mutation requested local mutation
    /// @param cancellation cooperative pre-commit cancellation
    /// @return actual refreshed state and optional mutation failure
    /// @throws IOException when the mandatory follow-up refresh fails
    ModCatalogMutationResult mutateAndRefresh(
            ModCatalogMutation mutation,
            LoadCancellation cancellation) throws IOException;
}
