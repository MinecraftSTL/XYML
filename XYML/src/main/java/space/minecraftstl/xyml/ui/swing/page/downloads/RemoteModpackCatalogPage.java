// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
