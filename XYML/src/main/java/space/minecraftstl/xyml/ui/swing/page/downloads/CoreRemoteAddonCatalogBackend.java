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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.setting.DownloadProviders;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/// Bridges explicit Swing catalog commands to the existing Core Modrinth and CurseForge repositories.
///
/// This bridge is intentionally synchronous. The owning Swing panel schedules all calls on its
/// injected worker executor, and construction only retains a download-provider reference.
@NotNullByDefault
public final class CoreRemoteAddonCatalogBackend implements RemoteAddonCatalogBackend {
    /// Launcher-wide provider used for Core source calls and subsequent candidate URL generation.
    private final DownloadProvider downloadProvider;

    /// Creates a bridge using the current launcher-wide download provider.
    public CoreRemoteAddonCatalogBackend() {
        this(DownloadProviders.getDownloadProvider());
    }

    /// Creates a bridge with an explicit Core provider for deterministic integration tests.
    ///
    /// @param downloadProvider provider passed unchanged to Core source operations
    public CoreRemoteAddonCatalogBackend(DownloadProvider downloadProvider) {
        this.downloadProvider = Objects.requireNonNull(downloadProvider, "downloadProvider");
    }

    /// Loads the selected provider's category tree for the requested content kind.
    ///
    /// @param kind requested content kind
    /// @param source selected provider
    /// @return immutable provider-ordered category roots
    /// @throws IOException when the provider cannot load categories
    @Override
    public @Unmodifiable List<RemoteAddonRepository.Category> loadCategories(
            RemoteAddonCatalogKind kind,
            RemoteAddonCatalogSource source) throws IOException {
        RemoteAddonCatalogKind requestedKind = Objects.requireNonNull(kind, "kind");
        RemoteAddonCatalogSource requestedSource = Objects.requireNonNull(source, "source");
        return requestedSource.repository(requestedKind).getCategories().toList();
    }

    /// Queries one provider page with the exact user-measured viewport row count.
    ///
    /// @param query explicit source request
    /// @return immutable page preserving result provenance
    /// @throws IOException when the selected Core repository fails
    @Override
    public RemoteAddonCatalogPage search(RemoteAddonCatalogQuery query) throws IOException {
        RemoteAddonCatalogQuery request = Objects.requireNonNull(query, "query");
        RemoteAddonRepository repository = request.source().repository(request.kind());
        RemoteAddonRepository.SearchResult result = repository.search(
                downloadProvider,
                request.gameVersion(),
                request.category(),
                request.pageOffset(),
                request.pageSize(),
                request.searchText(),
                request.sortType(),
                RemoteAddonRepository.SortOrder.DESC);
        @Unmodifiable List<RemoteAddonCatalogItem> items = result.getResults()
                .map(addon -> new RemoteAddonCatalogItem(addon, request.kind(), request.source()))
                .toList();
        return new RemoteAddonCatalogPage(items, request.pageOffset(), Math.max(0, result.getTotalPages()));
    }

    /// Resolves every version for one selected result through its original Core repository.
    ///
    /// @param item selected immutable project value
    /// @return immutable provider-ordered version list
    /// @throws IOException when the provider cannot resolve project versions
    @Override
    public @Unmodifiable List<RemoteAddon.Version> loadVersions(RemoteAddonCatalogItem item) throws IOException {
        RemoteAddonCatalogItem selected = Objects.requireNonNull(item, "item");
        return selected.addon().data().loadVersions(
                selected.source().repository(selected.kind()),
                downloadProvider).toList();
    }

    /// {@inheritDoc}
    @Override
    public @Nullable String loadChangelog(RemoteAddonCatalogItem item, RemoteAddon.Version version) throws IOException {
        RemoteAddonCatalogItem selected = Objects.requireNonNull(item, "item");
        RemoteAddon.Version selectedVersion = Objects.requireNonNull(version, "version");
        RemoteAddonRepository repository = selected.source().repository(selected.kind());
        return repository.getAddonChangelog(downloadProvider, selectedVersion.projectId(), selectedVersion.versionId());
    }

    /// {@inheritDoc}
    @Override
    public URI versionPage(RemoteAddonCatalogItem item, RemoteAddon.Version version) throws IOException {
        RemoteAddonCatalogItem selected = Objects.requireNonNull(item, "item");
        return URI.create(selected.source().repository(selected.kind())
                .getVersionPageUrl(Objects.requireNonNull(version, "version")));
    }
}
