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
