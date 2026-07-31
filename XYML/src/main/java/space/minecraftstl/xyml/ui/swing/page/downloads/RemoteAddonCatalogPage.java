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

/// One immutable provider page of remote add-on or world projects.
///
/// Core providers expose a total page count rather than a total result count. The Swing surface
/// uses that count only for explicit adjacent-page commands while its local list stays viewport lazy.
///
/// @param items immutable rows returned by one provider page
/// @param pageOffset zero-based represented page
/// @param totalPages provider total page count, including zero for an empty search
@NotNullByDefault
public record RemoteAddonCatalogPage(
        @Unmodifiable List<RemoteAddonCatalogItem> items,
        int pageOffset,
        int totalPages) {
    /// Defensively snapshots rows and validates provider pagination metadata.
    public RemoteAddonCatalogPage {
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
