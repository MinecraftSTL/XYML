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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.RemoteAddon;

import java.io.IOException;
import java.util.List;

/// Blocking Core gateway for remote add-on and world catalogs.
///
/// Implementations may access the network, so callers must invoke them only from a worker after an
/// explicit search, explicit page transition, or selection of a materialized sparse result row.
@NotNullByDefault
public interface RemoteAddonCatalogBackend {
    /// Searches one exact source page for the requested content category.
    ///
    /// @param query explicit user-requested category, provider, criteria, and viewport page size
    /// @return immutable provider result page
    /// @throws IOException when the provider cannot complete the request
    RemoteAddonCatalogPage search(RemoteAddonCatalogQuery query) throws IOException;

    /// Loads downloadable project versions after a user selects a materialized result row.
    ///
    /// @param item selected remote project and source provenance
    /// @return immutable provider-ordered downloadable versions
    /// @throws IOException when the provider cannot complete the request
    @Unmodifiable List<RemoteAddon.Version> loadVersions(RemoteAddonCatalogItem item) throws IOException;
}
