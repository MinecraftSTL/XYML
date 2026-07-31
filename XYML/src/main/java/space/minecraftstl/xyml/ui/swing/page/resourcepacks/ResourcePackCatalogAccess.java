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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/// Blocking local source contract required by the asynchronous resource-pack catalog model.
@NotNullByDefault
interface ResourcePackCatalogAccess {
    /// Loads only supported state and sorted candidate paths.
    ///
    /// @param cancellation cooperative index cancellation
    /// @return shallow supported or unsupported index
    /// @throws IOException when local index access fails
    ResourcePackCatalogIndex loadIndex(LoadCancellation cancellation) throws IOException;

    /// Resolves exactly the supplied paths in the supplied order.
    ///
    /// @param paths exact normalized viewport paths
    /// @param cancellation cooperative viewport cancellation
    /// @return one row per path in identical order
    /// @throws IOException when shared local state cannot be read
    @Unmodifiable List<ResourcePackCatalogItem> loadItems(
            @Unmodifiable List<Path> paths,
            LoadCancellation cancellation) throws IOException;

    /// Applies one write and rescans the exact shallow index before releasing source access.
    ///
    /// Implementations may create private, fully cleaned staging artifacts before `commitPoint`,
    /// but must call it immediately before the first potentially irreversible catalog change.
    /// A mutation failure is returned with the successfully refreshed index; an exception is
    /// reserved for cancellation or failure to obtain that mandatory index.
    ///
    /// @param mutation immutable requested write
    /// @param cancellation cooperative pre-commit cancellation
    /// @param commitPoint callback crossing the non-cancellable irreversible-change boundary
    /// @return refreshed exact index and optional mutation failure
    /// @throws IOException when the mandatory follow-up index cannot be obtained
    ResourcePackCatalogMutationAccessResult mutateAndLoadIndex(
            ResourcePackCatalogMutationRequest mutation,
            LoadCancellation cancellation,
            Runnable commitPoint) throws IOException;
}
