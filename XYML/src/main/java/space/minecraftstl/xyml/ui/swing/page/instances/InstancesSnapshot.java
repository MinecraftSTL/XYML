// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.OptionalInt;

/// Immutable installed-instance page state independent of a UI toolkit.
///
/// The selected index addresses the source order identified by `contentRevision`. Refreshing,
/// status, and selection-only changes must not increment that revision.
///
/// @param selectedIndex selected source index, or empty when no instance is selected
/// @param itemCount exact item count belonging to this content revision
/// @param contentRevision revision incremented after item count, order, or row content changes
/// @param statusText current repository status text
/// @param refreshing whether a repository refresh is running
/// @param listEnabled whether the user may change selection
/// @param refreshEnabled whether a refresh command may start
/// @param addEnabled whether the add-instance workflow is available
/// @param manageEnabled whether the selected instance may be managed
@NotNullByDefault
public record InstancesSnapshot(
        OptionalInt selectedIndex,
        int itemCount,
        long contentRevision,
        String statusText,
        boolean refreshing,
        boolean listEnabled,
        boolean refreshEnabled,
        boolean addEnabled,
        boolean manageEnabled) {
    /// Validates one page state.
    public InstancesSnapshot {
        Objects.requireNonNull(selectedIndex, "selectedIndex");
        Objects.requireNonNull(statusText, "statusText");
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
        if (refreshing && refreshEnabled) {
            throw new IllegalArgumentException("Refreshing state cannot accept another refresh command");
        }
    }
}
