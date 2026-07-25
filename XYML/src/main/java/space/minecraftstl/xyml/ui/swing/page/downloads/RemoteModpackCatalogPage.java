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

import java.util.List;
import java.util.Objects;

/// One immutable backend page of remote modpack projects.
///
/// The Core repositories report total pages rather than a total result count. The Swing page uses
/// this value for explicit next/previous controls while its local sparse list renders only rows in
/// the visible portion of this page.
///
/// @param items immutable remote projects returned for this backend page
/// @param pageOffset zero-based page index represented by the items
/// @param totalPages total available backend pages, including zero for no results
@NotNullByDefault
public record RemoteModpackCatalogPage(
        @Unmodifiable List<RemoteModpackCatalogItem> items,
        int pageOffset,
        int totalPages) {
    /// Defensively snapshots result rows and validates backend pagination metadata.
    public RemoteModpackCatalogPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (pageOffset < 0) {
            throw new IllegalArgumentException("pageOffset must not be negative");
        }
        if (totalPages < 0) {
            throw new IllegalArgumentException("totalPages must not be negative");
        }
        if (totalPages == 0 && !items.isEmpty()) {
            throw new IllegalArgumentException("An empty catalog cannot contain result items");
        }
        if (totalPages > 0 && pageOffset >= totalPages) {
            throw new IllegalArgumentException("pageOffset must belong to totalPages");
        }
    }
}
