// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.OptionalInt;

/// Immutable game-version catalog state independent of a UI toolkit.
///
/// The selected index addresses the filtered source order identified by `contentRevision`.
/// Lifecycle, query, filter, and selection-only changes do not increment that revision unless the
/// visible item order or content actually changes.
///
/// @param selectedIndex selected filtered index, or empty when the stable selection is hidden or absent
/// @param itemCount exact visible item count for this content revision
/// @param contentRevision revision incremented only after visible row content or order changes
/// @param status current source-load lifecycle
/// @param statusText localized text matching the current lifecycle and visible result state
/// @param query current case-insensitive version-ID query
/// @param filter current game-version kind filter
/// @param listEnabled whether visible rows may be selected
/// @param refreshEnabled whether a new source generation may be requested from the UI
@NotNullByDefault
public record GameVersionCatalogSnapshot(
        OptionalInt selectedIndex,
        int itemCount,
        long contentRevision,
        GameVersionCatalogStatus status,
        String statusText,
        String query,
        GameVersionFilter filter,
        boolean listEnabled,
        boolean refreshEnabled) {
    /// Validates one catalog state.
    public GameVersionCatalogSnapshot {
        Objects.requireNonNull(selectedIndex, "selectedIndex");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(statusText, "statusText");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(filter, "filter");
        if (itemCount < 0) {
            throw new IllegalArgumentException("Item count cannot be negative");
        }
        if (contentRevision < 0L) {
            throw new IllegalArgumentException("Content revision cannot be negative");
        }
        if (selectedIndex.isPresent() && selectedIndex.getAsInt() < 0) {
            throw new IllegalArgumentException("Selected index cannot be negative");
        }
        if (selectedIndex.isPresent() && selectedIndex.getAsInt() >= itemCount) {
            throw new IllegalArgumentException("Selected index must be inside the exact item count");
        }
        if (status == GameVersionCatalogStatus.LOADING && refreshEnabled) {
            throw new IllegalArgumentException("Loading state cannot accept another UI refresh command");
        }
    }
}
