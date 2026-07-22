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
