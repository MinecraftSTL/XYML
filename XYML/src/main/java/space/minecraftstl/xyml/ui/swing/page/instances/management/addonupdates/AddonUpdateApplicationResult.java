// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Immutable result of applying one exact ordered add-on update selection.
///
/// Per-item failures are values in this result and do not fail the aggregate task. The two lists
/// preserve the relative order of their corresponding items in the caller's original selection.
///
/// @param successfulUpdates exact selected items whose replacement download completed
/// @param failures exact selected items whose update or state restoration failed
@NotNullByDefault
public record AddonUpdateApplicationResult(
        @Unmodifiable List<AddonUpdateItem> successfulUpdates,
        @Unmodifiable List<AddonUpdateApplicationFailure> failures) {
    /// Defensively snapshots both ordered result partitions.
    public AddonUpdateApplicationResult {
        successfulUpdates = List.copyOf(Objects.requireNonNull(
                successfulUpdates,
                "successfulUpdates"));
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
    }

    /// Returns the total number of selected updates represented by this result.
    ///
    /// @return successful plus failed item count
    public int attemptedCount() {
        return successfulUpdates.size() + failures.size();
    }

    /// Returns whether at least one selected update failed.
    ///
    /// @return whether the failure list is non-empty
    public boolean hasFailures() {
        return !failures.isEmpty();
    }
}
