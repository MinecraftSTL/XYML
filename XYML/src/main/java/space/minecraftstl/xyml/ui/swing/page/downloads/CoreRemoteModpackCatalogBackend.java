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
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.setting.DownloadProviders;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/// Bridges explicit Swing catalog requests to the existing Core CurseForge and Modrinth repositories.
///
/// This class is deliberately synchronous because the owning panel schedules each call on its
/// injected background executor. Constructing the bridge only retains the launcher-wide provider;
/// it never performs source discovery, search, or version resolution by itself.
@NotNullByDefault
public final class CoreRemoteModpackCatalogBackend implements RemoteModpackCatalogBackend {
    /// Stable download-provider wrapper used by Core source API calls and their candidate URLs.
    private final DownloadProvider downloadProvider;

    /// Creates a bridge using the current launcher-wide download provider.
    public CoreRemoteModpackCatalogBackend() {
        this(DownloadProviders.getDownloadProvider());
    }

    /// Creates a bridge with an explicit Core download provider.
    ///
    /// @param downloadProvider provider passed unchanged to source repository operations
    public CoreRemoteModpackCatalogBackend(DownloadProvider downloadProvider) {
        this.downloadProvider = Objects.requireNonNull(downloadProvider, "downloadProvider");
    }

    /// Loads the selected provider's remote modpack category tree.
    ///
    /// @param source selected provider
    /// @return immutable provider-ordered category roots
    /// @throws IOException when the provider cannot load categories
    @Override
    public @Unmodifiable List<RemoteAddonRepository.Category> loadCategories(
            RemoteModpackCatalogSource source) throws IOException {
        RemoteModpackCatalogSource requestedSource = Objects.requireNonNull(source, "source");
        return requestedSource.repository().getCategories().toList();
    }

    /// Queries one server page with the exact viewport-derived page size requested by the user.
    ///
    /// @param query explicit source, filters, offset, and measured row count
    /// @return immutable row page preserving the requested source and page identity
    /// @throws IOException when the selected Core repository cannot complete the request
    @Override
    public RemoteModpackCatalogPage search(RemoteModpackCatalogQuery query) throws IOException {
        RemoteModpackCatalogQuery request = Objects.requireNonNull(query, "query");
        RemoteAddonRepository repository = request.source().repository();
        RemoteAddonRepository.SearchResult result = repository.search(
                downloadProvider,
                request.gameVersion(),
                request.category(),
                request.pageOffset(),
                request.pageSize(),
                request.searchText(),
                request.sortType(),
                RemoteAddonRepository.SortOrder.DESC);
        @Unmodifiable List<RemoteModpackCatalogItem> items = result.getResults()
                .map(addon -> new RemoteModpackCatalogItem(addon, request.source()))
                .toList();
        return new RemoteModpackCatalogPage(items, request.pageOffset(), Math.max(0, result.getTotalPages()));
    }

    /// Resolves all installable versions of a project after the user selects its loaded list row.
    ///
    /// @param item loaded selected remote project
    /// @return immutable Core version values in source order
    /// @throws IOException when the selected source cannot load project versions
    @Override
    public @Unmodifiable List<RemoteAddon.Version> loadVersions(RemoteModpackCatalogItem item) throws IOException {
        RemoteModpackCatalogItem selected = Objects.requireNonNull(item, "item");
        return selected.addon().data().loadVersions(selected.source().repository(), downloadProvider).toList();
    }
}
