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
        assertFalse(cache.get(query(8, 4)).isPresent());
        assertTrue(cache.get(query(0, 5)).isPresent());
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
        return new RemoteAddonCatalogQuery(
                RemoteAddonCatalogKind.MOD,
                RemoteAddonCatalogSource.MODRINTH,
                "cache fixture",
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
