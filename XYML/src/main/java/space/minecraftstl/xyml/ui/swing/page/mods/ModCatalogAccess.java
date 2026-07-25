/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
