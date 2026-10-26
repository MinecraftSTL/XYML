// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the finite exact-query cache used only after explicit catalog page visits.
@NotNullByDefault
final class RemoteAddonCatalogPageCacheTest {
    /// Keeps the eight closest user-visited pages and starts a new scope after viewport geometry changes.
    @Test
    void retainsClosestVisitedPagesWithinOneExactViewportScope() {
        RemoteAddonCatalogPageCache cache = new RemoteAddonCatalogPageCache();

        assertEquals(8, RemoteAddonCatalogPageCache.MAXIMUM_PAGE_COUNT);
        for (int pageOffset = 0; pageOffset < RemoteAddonCatalogPageCache.MAXIMUM_PAGE_COUNT; pageOffset++) {
            cache.put(query(pageOffset, 4), page(pageOffset, 20));
        }

        assertTrue(cache.get(query(0, 4)).isPresent());
        cache.put(query(8, 4), page(8, 20));

        assertFalse(cache.get(query(0, 4)).isPresent());
        assertTrue(cache.get(query(1, 4)).isPresent());
        assertTrue(cache.get(query(8, 4)).isPresent());
        assertFalse(cache.get(query(0, 5)).isPresent());

        cache.put(query(0, 5), page(0, 20));
        assertTrue(cache.get(query(8, 4)).isPresent());
        assertTrue(cache.get(query(0, 5)).isPresent());
    }

    /// Retains distinct dependency searches while keeping each exact query bounded.
    @Test
    void retainsDistinctSearchQueries() {
        RemoteAddonCatalogPageCache cache = new RemoteAddonCatalogPageCache();

        cache.put(query("first-dependency", 0, 4), page(0, 20));
        cache.put(query("second-dependency", 0, 4), page(0, 20));

        assertTrue(cache.get(query("first-dependency", 0, 4)).isPresent());
        assertTrue(cache.get(query("second-dependency", 0, 4)).isPresent());
        assertEquals(2, cache.size());
    }

    /// Evicts the least recently used query pages after the global entry bound is reached.
    @Test
    void evictsLeastRecentlyUsedEntriesAboveGlobalBound() {
        RemoteAddonCatalogPageCache cache = new RemoteAddonCatalogPageCache();

        for (int scope = 0; scope < 9; scope++) {
            for (int pageOffset = 0; pageOffset < RemoteAddonCatalogPageCache.MAXIMUM_PAGE_COUNT; pageOffset++) {
                cache.put(
                        query("dependency-" + scope, pageOffset, 4),
                        page(pageOffset, 20));
            }
        }

        assertEquals(RemoteAddonCatalogPageCache.MAXIMUM_ENTRY_COUNT, cache.size());
        boolean firstScopeRetained = false;
        for (int pageOffset = 0; pageOffset < RemoteAddonCatalogPageCache.MAXIMUM_PAGE_COUNT; pageOffset++) {
            firstScopeRetained |= cache.get(query("dependency-0", pageOffset, 4)).isPresent();
        }
        assertFalse(firstScopeRetained);
        assertTrue(cache.get(query("dependency-8", 7, 4)).isPresent());
    }

    /// Drops previously visited offsets that no longer exist when a provider reports fewer total pages.
    @Test
    void followsShrinkingProviderPageBoundaries() {
        RemoteAddonCatalogPageCache cache = new RemoteAddonCatalogPageCache();
        for (int pageOffset = 0; pageOffset < 6; pageOffset++) {
            cache.put(query(pageOffset, 4), page(pageOffset, 20));
        }

        cache.put(query(2, 4), page(2, 3));

        assertTrue(cache.get(query(0, 4)).isPresent());
        assertTrue(cache.get(query(1, 4)).isPresent());
        assertTrue(cache.get(query(2, 4)).isPresent());
        assertFalse(cache.get(query(3, 4)).isPresent());
        assertFalse(cache.get(query(5, 4)).isPresent());
    }

    /// Creates one explicit page query with fixed filters and a chosen viewport-derived size.
    ///
    /// @param pageOffset zero-based visited provider page
    /// @param pageSize visible row count passed to the provider
    /// @return immutable exact cache key query
    private static RemoteAddonCatalogQuery query(int pageOffset, int pageSize) {
        return query("cache fixture", pageOffset, pageSize);
    }

    /// Creates one explicit page query for a selected search text.
    ///
    /// @param searchText provider search text
    /// @param pageOffset zero-based visited provider page
    /// @param pageSize visible row count passed to the provider
    /// @return immutable exact cache key query
    private static RemoteAddonCatalogQuery query(String searchText, int pageOffset, int pageSize) {
        return new RemoteAddonCatalogQuery(
                RemoteAddonCatalogKind.MOD,
                RemoteAddonCatalogSource.MODRINTH,
                searchText,
                "1.20.1",
                null,
                RemoteAddonRepository.SortType.POPULARITY,
                pageOffset,
                pageSize);
    }

    /// Creates one empty successful provider page for a known page index.
    ///
    /// @param pageOffset zero-based represented page
    /// @param totalPages actual provider page count
    /// @return immutable fixture page
    private static RemoteAddonCatalogPage page(int pageOffset, int totalPages) {
        return new RemoteAddonCatalogPage(List.of(), pageOffset, totalPages);
    }
}
