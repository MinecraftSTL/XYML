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
import space.minecraftstl.xyml.addon.RemoteAddonRepository;

import java.io.IOException;
import java.util.List;

/// Blocking Core gateway used by the remote modpack Swing page.
///
/// Implementations may use network access and run only on the panel's background executor for
/// display category discovery, explicit search or page transitions, and selected-item versions.
@NotNullByDefault
public interface RemoteModpackCatalogBackend {
    /// Loads provider categories when the catalog becomes displayable or its source changes.
    ///
    /// @param source selected remote provider
    /// @return immutable provider-ordered category roots
    /// @throws IOException when the provider cannot load categories
    @Unmodifiable List<RemoteAddonRepository.Category> loadCategories(
            RemoteModpackCatalogSource source) throws IOException;

    /// Searches exactly one source page of remote modpack projects.
    ///
    /// @param query explicit user-requested search parameters
    /// @return immutable page of Core-backed remote project values
    /// @throws IOException when the source request fails
    RemoteModpackCatalogPage search(RemoteModpackCatalogQuery query) throws IOException;

    /// Loads installable versions for one user-selected remote project.
    ///
    /// @param item selected project and source provenance
    /// @return immutable provider-ordered installable versions
    /// @throws IOException when the source request fails
    @Unmodifiable List<RemoteAddon.Version> loadVersions(RemoteModpackCatalogItem item) throws IOException;
}
