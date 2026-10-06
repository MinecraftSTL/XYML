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
package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/// Immutable visible state for one local shader-pack catalog.
///
/// @param selectedIndex selected item index, or empty when no loaded item is selected
/// @param itemCount exact loaded item count
/// @param contentRevision revision incremented whenever visible content changes
/// @param status latest disk-scan lifecycle
/// @param statusText localized status or failure detail
/// @param writeStatus latest serialized mutation lifecycle
/// @param writeStatusText localized mutation progress or failure detail
/// @param items immutable loaded rows in stable file-name order
/// @param availableBackends backends available for enable/disable operations
@NotNullByDefault
public record ShaderPackCatalogSnapshot(
        OptionalInt selectedIndex,
        int itemCount,
        long contentRevision,
        ShaderPackCatalogStatus status,
        String statusText,
        ShaderPackCatalogWriteStatus writeStatus,
        String writeStatusText,
        @Unmodifiable List<ShaderPackCatalogItem> items,
        @Unmodifiable Set<ShaderPackBackend> availableBackends) {
    /// Validates and freezes one catalog snapshot.
    public ShaderPackCatalogSnapshot {
        Objects.requireNonNull(selectedIndex, "selectedIndex");
        Objects.requireNonNull(status, "status");
        statusText = Objects.requireNonNull(statusText, "statusText");
        Objects.requireNonNull(writeStatus, "writeStatus");
        writeStatusText = Objects.requireNonNull(writeStatusText, "writeStatusText");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        availableBackends = Set.copyOf(Objects.requireNonNull(availableBackends, "availableBackends"));
        if (itemCount != items.size()) {
            throw new IllegalArgumentException("itemCount must match items size");
        }
        if (selectedIndex.isPresent()
                && (selectedIndex.getAsInt() < 0 || selectedIndex.getAsInt() >= itemCount)) {
            throw new IllegalArgumentException("selectedIndex is outside the item range");
        }
        if (contentRevision < 0L) {
            throw new IllegalArgumentException("contentRevision must not be negative");
        }
    }

    /// Creates the initial idle snapshot.
    ///
    /// @param idleText localized idle text
    /// @return initial snapshot
    public static ShaderPackCatalogSnapshot idle(String idleText) {
        return new ShaderPackCatalogSnapshot(
                OptionalInt.empty(),
                0,
                0L,
                ShaderPackCatalogStatus.IDLE,
                Objects.requireNonNull(idleText, "idleText"),
                ShaderPackCatalogWriteStatus.IDLE,
                "",
                List.of(),
                Set.of());
    }
}
