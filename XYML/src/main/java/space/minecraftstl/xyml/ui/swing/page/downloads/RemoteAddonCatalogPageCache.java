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

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

/// Bounded access-order cache of provider pages explicitly visited by a remote add-on catalog user.
///
/// The cache never schedules speculation or prefetching. It can only store a successful page after
/// that exact page was requested by the user. The key includes viewport-derived `pageSize`, because
/// changing the visible result geometry changes server page boundaries and makes an older page unsafe
/// to reuse for a later request.
@NotNullByDefault
final class RemoteAddonCatalogPageCache {
    /// Number of user-visited provider pages retained per live panel.
    ///
    /// Six pages is the midpoint of the requested four-to-eight neighboring-page retention window:
    /// it holds the current browsing context plus a short backtrack history without turning a single
    /// catalog session into unbounded memory retention or generating any unsolicited source traffic.
    static final int MAXIMUM_PAGE_COUNT = 6;

    /// Maximum number of query-and-page entries retained in access order.
    private final int capacity;

    /// Access-order page store; the eldest entry is the least recently reused user-visited page.
    private final LinkedHashMap<CacheKey, RemoteAddonCatalogPage> entries = new LinkedHashMap<>(
            MAXIMUM_PAGE_COUNT,
            0.75F,
            true);

    /// Creates the production-sized cache.
    RemoteAddonCatalogPageCache() {
        this(MAXIMUM_PAGE_COUNT);
    }

    /// Creates a bounded cache with explicit capacity for deterministic tests.
    ///
    /// @param capacity positive maximum number of retained pages
    RemoteAddonCatalogPageCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    /// Returns one previously user-visited page only for an exact query-and-viewport key.
    ///
    /// @param query current explicit source query
    /// @return retained page, or empty after a key miss
    Optional<RemoteAddonCatalogPage> get(RemoteAddonCatalogQuery query) {
        return Optional.ofNullable(entries.get(new CacheKey(Objects.requireNonNull(query, "query"))));
    }

    /// Stores one successful user-requested provider page and evicts only the least recently used entry.
    ///
    /// @param query exact explicit query that produced the page
    /// @param page returned provider page
    void put(RemoteAddonCatalogQuery query, RemoteAddonCatalogPage page) {
        entries.put(
                new CacheKey(Objects.requireNonNull(query, "query")),
                Objects.requireNonNull(page, "page"));
        while (entries.size() > capacity) {
            CacheKey eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
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
    /// @param pageOffset requested provider page index
    /// @param pageSize measured visible row count sent to the provider
    @NotNullByDefault
    private record CacheKey(
            RemoteAddonCatalogKind kind,
            RemoteAddonCatalogSource source,
            String searchText,
            String gameVersion,
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
                    query.pageOffset(),
                    query.pageSize());
        }
    }
}
