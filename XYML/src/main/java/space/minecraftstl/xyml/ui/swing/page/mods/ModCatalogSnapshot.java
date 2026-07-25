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
package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.OptionalInt;

/// Immutable visible state for one installed-Mod catalog.
///
/// @param selectedIndex selected filtered index, or empty without a current selection
/// @param itemCount exact filtered count, or empty until a refresh succeeds
/// @param contentRevision revision identifying the exact filtered viewport source
/// @param status latest index lifecycle
/// @param statusText localized index status
/// @param writeStatus latest mutation lifecycle
/// @param writeStatusText localized mutation status, empty while idle
/// @param searchQuery current normalized search query
/// @param filter current enabled-state filter
/// @param listEnabled whether rows may be selected or changed
/// @param refreshEnabled whether a fresh full refresh may start
@NotNullByDefault
public record ModCatalogSnapshot(
        OptionalInt selectedIndex,
        OptionalInt itemCount,
        long contentRevision,
        ModCatalogStatus status,
        String statusText,
        ModCatalogWriteStatus writeStatus,
        String writeStatusText,
        String searchQuery,
        ModCatalogFilter filter,
        boolean listEnabled,
        boolean refreshEnabled) {
    /// Validates atomically published catalog state.
    public ModCatalogSnapshot {
        Objects.requireNonNull(selectedIndex, "selectedIndex");
        Objects.requireNonNull(itemCount, "itemCount");
        Objects.requireNonNull(status, "status");
        statusText = Objects.requireNonNull(statusText, "statusText");
        Objects.requireNonNull(writeStatus, "writeStatus");
        writeStatusText = Objects.requireNonNull(writeStatusText, "writeStatusText");
        searchQuery = Objects.requireNonNull(searchQuery, "searchQuery");
        Objects.requireNonNull(filter, "filter");
        if (contentRevision < 0L) {
            throw new IllegalArgumentException("contentRevision must not be negative");
        }
        if (selectedIndex.isPresent()
                && (itemCount.isEmpty()
                || selectedIndex.getAsInt() < 0
                || selectedIndex.getAsInt() >= itemCount.getAsInt())) {
            throw new IllegalArgumentException("selectedIndex must belong to itemCount");
        }
        if (status == ModCatalogStatus.READY && itemCount.isEmpty()) {
            throw new IllegalArgumentException("Ready catalog must expose an exact item count");
        }
        if (status != ModCatalogStatus.READY && listEnabled) {
            throw new IllegalArgumentException("Only a ready catalog may enable its list");
        }
        if (listEnabled && (itemCount.isEmpty() || itemCount.getAsInt() == 0)) {
            throw new IllegalArgumentException("An empty catalog cannot enable its list");
        }
        if (writeStatus == ModCatalogWriteStatus.BUSY && (listEnabled || refreshEnabled)) {
            throw new IllegalArgumentException("Busy mutation must disable list and refresh");
        }
        if (writeStatus == ModCatalogWriteStatus.IDLE && !writeStatusText.isEmpty()) {
            throw new IllegalArgumentException("Idle mutation must not expose status text");
        }
        if (writeStatus != ModCatalogWriteStatus.IDLE && writeStatusText.isBlank()) {
            throw new IllegalArgumentException("Active mutation state requires status text");
        }
    }
}
