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

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

/// Viewport-scoped cache of nearby provider pages explicitly visited by a catalog user.
///
/// The cache never schedules speculation or prefetching. It can only store a successful page after
/// that exact page was requested by the user. The key includes viewport-derived `pageSize`, because
/// changing the visible result geometry changes server page boundaries and starts a new cache scope.
@NotNullByDefault
final class RemoteAddonCatalogPageCache {
    /// Hard upper bound for user-visited pages retained within one exact query and viewport scope.
    static final int MAXIMUM_PAGE_COUNT = 8;

    /// Access-order store used only to break ties between equally distant visited pages.
    private final LinkedHashMap<CacheKey, RemoteAddonCatalogPage> entries = new LinkedHashMap<>(
            MAXIMUM_PAGE_COUNT,
            0.75F,
            true);

    /// Returns one previously user-visited page only for an exact query-and-viewport key.
    ///
    /// @param query current explicit source query
    /// @return retained page, or empty after a key miss
    Optional<RemoteAddonCatalogPage> get(RemoteAddonCatalogQuery query) {
        return Optional.ofNullable(entries.get(new CacheKey(Objects.requireNonNull(query, "query"))));
    }

    /// Stores one successful page and evicts the farthest visited page from the current offset.
    ///
    /// A query or viewport-size change starts a new scope. Within one scope, at most eight actually
    /// visited pages are retained, further limited by the provider's real total page count; no page
    /// is prefetched and no guessed default row count participates in retention.
    ///
    /// @param query exact explicit query that produced the page
    /// @param page returned provider page
    void put(RemoteAddonCatalogQuery query, RemoteAddonCatalogPage page) {
        RemoteAddonCatalogQuery request = Objects.requireNonNull(query, "query");
        RemoteAddonCatalogPage result = Objects.requireNonNull(page, "page");
        CacheKey currentKey = new CacheKey(request);
        entries.entrySet().removeIf(entry -> !entry.getKey().sameScope(currentKey)
                || result.totalPages() > 0 && entry.getKey().pageOffset() >= result.totalPages());
        entries.put(currentKey, result);

        int capacity = Math.min(MAXIMUM_PAGE_COUNT, Math.max(1, result.totalPages()));
        while (entries.size() > capacity) {
            @Nullable CacheKey eviction = null;
            int greatestDistance = -1;
            for (CacheKey key : entries.keySet()) {
                int distance = Math.abs(key.pageOffset() - currentKey.pageOffset());
                if (distance > greatestDistance) {
                    eviction = key;
                    greatestDistance = distance;
                }
            }
            entries.remove(Objects.requireNonNull(eviction, "eviction"));
        }
    }

    /// Clears all retained pages during panel closure.
    void clear() {
        entries.clear();
    }

    /// Exact provider-page identity, including the viewport-derived source page-size boundary.
    ///
    /// @param kind acquisition category
    /// @param source selected provider
    /// @param searchText normalized project filter
    /// @param gameVersion normalized Minecraft-version filter
    /// @param categoryId selected provider category identifier, or null for all categories
    /// @param sortType selected result ordering
    /// @param pageOffset requested provider page index
    /// @param pageSize measured visible row count sent to the provider
    @NotNullByDefault
    private record CacheKey(
            RemoteAddonCatalogKind kind,
            RemoteAddonCatalogSource source,
            String searchText,
            String gameVersion,
            @Nullable String categoryId,
            RemoteAddonRepository.SortType sortType,
            int pageOffset,
            int pageSize) {
        /// Snapshots every relevant server-page boundary from an immutable user query.
        ///
        /// @param query explicit query to key
        private CacheKey(RemoteAddonCatalogQuery query) {
            this(
                    query.kind(),
                    query.source(),
                    query.searchText(),
                    query.gameVersion(),
                    query.category() == null ? null : query.category().id(),
                    query.sortType(),
                    query.pageOffset(),
                    query.pageSize());
        }

        /// Returns whether another page belongs to the same exact criteria and viewport scope.
        ///
        /// @param other candidate cache key
        /// @return true when only the page offset may differ
        private boolean sameScope(CacheKey other) {
            CacheKey candidate = Objects.requireNonNull(other, "other");
            return kind == candidate.kind
                    && source == candidate.source
                    && searchText.equals(candidate.searchText)
                    && gameVersion.equals(candidate.gameVersion)
                    && Objects.equals(categoryId, candidate.categoryId)
                    && sortType == candidate.sortType
                    && pageSize == candidate.pageSize;
        }
    }
}
