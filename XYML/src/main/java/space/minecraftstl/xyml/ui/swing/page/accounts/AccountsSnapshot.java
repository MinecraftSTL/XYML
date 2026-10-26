// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.OptionalInt;

/// Minimal immutable state for the viewport-driven account list.
///
/// The selected index addresses the source order identified by `contentRevision`. Selection-only
/// transitions retain the current revision; descriptor count, order, or text changes increment it.
///
/// @param selectedIndex selected source index, or empty when no account is selected
/// @param itemCount exact account count belonging to this content revision
/// @param contentRevision non-negative revision of indexed row content
@NotNullByDefault
public record AccountsSnapshot(
        OptionalInt selectedIndex,
        int itemCount,
        long contentRevision) {
    /// Validates one minimal account-list snapshot.
    public AccountsSnapshot {
        Objects.requireNonNull(selectedIndex, "selectedIndex");
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
    }
}
