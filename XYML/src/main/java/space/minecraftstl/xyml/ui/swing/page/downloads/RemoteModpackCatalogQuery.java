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
import space.minecraftstl.xyml.addon.RemoteAddonRepository;

import java.util.Objects;

/// Immutable user-requested remote modpack search, including viewport-derived server page size.
///
/// The page creates this value only after a user command. `pageSize` is measured from the actual
/// visible list geometry at that moment, avoiding a made-up launcher-wide default row count.
///
/// @param source selected remote provider
/// @param searchText normalized optional user text filter
/// @param gameVersion normalized optional Minecraft version filter
/// @param category selected provider category, or null for all categories
/// @param sortType selected provider-supported result ordering
/// @param pageOffset zero-based backend page index
/// @param pageSize positive backend page size derived from the active viewport
@NotNullByDefault
public record RemoteModpackCatalogQuery(
        RemoteModpackCatalogSource source,
        String searchText,
        String gameVersion,
        @Nullable RemoteAddonRepository.Category category,
        RemoteAddonRepository.SortType sortType,
        int pageOffset,
        int pageSize) {
    /// Validates the selected source and normalizes free-form filter text.
    public RemoteModpackCatalogQuery {
        source = Objects.requireNonNull(source, "source");
        searchText = Objects.requireNonNull(searchText, "searchText").trim();
        gameVersion = Objects.requireNonNull(gameVersion, "gameVersion").trim();
        sortType = Objects.requireNonNull(sortType, "sortType");
        if (pageOffset < 0) {
            throw new IllegalArgumentException("pageOffset must not be negative");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
    }
}
