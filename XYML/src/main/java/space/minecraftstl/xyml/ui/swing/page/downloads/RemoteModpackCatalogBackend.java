// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
