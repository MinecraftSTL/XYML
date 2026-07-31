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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.OptionalInt;

/// Immutable visible state for one resource-pack catalog.
///
/// The selected index addresses the indexed path order identified by `contentRevision`. The exact
/// count remains unknown until a supported or unsupported index generation succeeds.
///
/// @param selectedIndex selected indexed path, or empty while absent or the index is unknown
/// @param itemCount exact indexed path count, or empty before an index generation succeeds
/// @param contentRevision revision incremented whenever indexed content is invalidated or replaced
/// @param status latest lazy disk-scan lifecycle
/// @param statusText localized status and optional failure detail
/// @param writeStatus latest serialized local-write lifecycle
/// @param writeStatusText localized write progress or failure detail, empty while idle
/// @param listEnabled whether visible rows may be selected
/// @param refreshEnabled whether a fresh disk scan may be requested
@NotNullByDefault
public record ResourcePackCatalogSnapshot(
        OptionalInt selectedIndex,
        OptionalInt itemCount,
        long contentRevision,
        ResourcePackCatalogStatus status,
        String statusText,
        ResourcePackCatalogWriteStatus writeStatus,
        String writeStatusText,
        boolean listEnabled,
        boolean refreshEnabled) {
    /// Validates one atomically published catalog snapshot.
    public ResourcePackCatalogSnapshot {
        Objects.requireNonNull(selectedIndex, "selectedIndex");
        Objects.requireNonNull(itemCount, "itemCount");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(statusText, "statusText");
        Objects.requireNonNull(writeStatus, "writeStatus");
        Objects.requireNonNull(writeStatusText, "writeStatusText");
        if (contentRevision < 0L) {
            throw new IllegalArgumentException("contentRevision must not be negative");
        }
        if (selectedIndex.isPresent()) {
            if (itemCount.isEmpty()
                    || selectedIndex.getAsInt() < 0
                    || selectedIndex.getAsInt() >= itemCount.getAsInt()) {
                throw new IllegalArgumentException("selectedIndex must be inside known itemCount");
            }
        }
        if (listEnabled && (itemCount.isEmpty() || itemCount.getAsInt() == 0)) {
            throw new IllegalArgumentException("An unknown or empty catalog cannot enable selection");
        }
        if (status == ResourcePackCatalogStatus.LOADING && refreshEnabled) {
            throw new IllegalArgumentException("Loading state cannot accept another refresh");
        }
        if (writeStatus == ResourcePackCatalogWriteStatus.BUSY
                && (listEnabled || refreshEnabled)) {
            throw new IllegalArgumentException("Busy write state must disable list and refresh");
        }
        if (writeStatus == ResourcePackCatalogWriteStatus.IDLE && !writeStatusText.isEmpty()) {
            throw new IllegalArgumentException("Idle write state must not expose status text");
        }
        if (writeStatus != ResourcePackCatalogWriteStatus.IDLE && writeStatusText.isBlank()) {
            throw new IllegalArgumentException("Active or failed write state requires status text");
        }
        if ((status == ResourcePackCatalogStatus.READY
                || status == ResourcePackCatalogStatus.UNSUPPORTED)
                && itemCount.isEmpty()) {
            throw new IllegalArgumentException("Terminal successful state requires exact itemCount");
        }
    }
}
