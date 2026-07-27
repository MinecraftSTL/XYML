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

import java.util.Objects;

/// Immutable explicit remote add-on search with a viewport-derived server page size.
///
/// Instances are created only from user actions. `pageSize` therefore reflects the result list's
/// actual visible geometry instead of a guessed default item count.
///
/// @param kind remote acquisition category
/// @param source selected provider
/// @param searchText normalized optional project filter
/// @param gameVersion normalized optional Minecraft-version filter
/// @param pageOffset zero-based provider page index
/// @param pageSize positive provider result count requested for the visible viewport
@NotNullByDefault
public record RemoteAddonCatalogQuery(
        RemoteAddonCatalogKind kind,
        RemoteAddonCatalogSource source,
        String searchText,
        String gameVersion,
        int pageOffset,
        int pageSize) {
    /// Validates local source selection and normalizes free-form criteria without querying a provider.
    public RemoteAddonCatalogQuery {
        kind = Objects.requireNonNull(kind, "kind");
        source = Objects.requireNonNull(source, "source");
        searchText = Objects.requireNonNull(searchText, "searchText").trim();
        gameVersion = Objects.requireNonNull(gameVersion, "gameVersion").trim();
        if (pageOffset < 0) {
            throw new IllegalArgumentException("pageOffset must not be negative");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        if (!source.supports(kind)) {
            throw new IllegalArgumentException("Selected source does not support the catalog kind");
        }
    }
}
