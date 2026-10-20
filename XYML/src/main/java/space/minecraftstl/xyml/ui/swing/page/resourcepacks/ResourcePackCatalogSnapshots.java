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

/// Builds immutable resource-pack snapshots without exposing transition arguments to callers.
@NotNullByDefault
final class ResourcePackCatalogSnapshots {
    /// Prevents utility-class construction.
    private ResourcePackCatalogSnapshots() {
    }

    /// Builds one snapshot while retaining the prior enabled prefix when possible.
    ///
    /// @param ignoredPrevious previous snapshot documenting this copy
    /// @param selectedIndex replacement selection
    /// @param itemCount replacement exact count or unknown
    /// @param contentRevision replacement content revision
    /// @param status replacement lifecycle
    /// @param statusText replacement localized status
    /// @param writeStatus replacement serialized-write lifecycle
    /// @param writeStatusText replacement write lifecycle text
    /// @param listEnabled replacement list enabled flag
    /// @param refreshEnabled replacement refresh enabled flag
    /// @return replacement snapshot
    static ResourcePackCatalogSnapshot copy(
            ResourcePackCatalogSnapshot ignoredPrevious,
            OptionalInt selectedIndex,
            OptionalInt itemCount,
            long contentRevision,
            ResourcePackCatalogStatus status,
            String statusText,
            ResourcePackCatalogWriteStatus writeStatus,
            String writeStatusText,
            boolean listEnabled,
            boolean refreshEnabled) {
        int retainedEnabledItemCount = itemCount.isPresent()
                ? Math.min(ignoredPrevious.enabledItemCount(), itemCount.getAsInt())
                : 0;
        return copy(
                ignoredPrevious,
                selectedIndex,
                itemCount,
                contentRevision,
                status,
                statusText,
                writeStatus,
                writeStatusText,
                listEnabled,
                refreshEnabled,
                retainedEnabledItemCount);
    }

    /// Builds one snapshot with an explicit enabled prefix size.
    ///
    /// @param ignoredPrevious previous snapshot documenting this copy
    /// @param selectedIndex replacement selection
    /// @param itemCount replacement exact count or unknown
    /// @param contentRevision replacement content revision
    /// @param status replacement lifecycle
    /// @param statusText replacement localized status
    /// @param writeStatus replacement serialized-write lifecycle
    /// @param writeStatusText replacement write lifecycle text
    /// @param listEnabled replacement list enabled flag
    /// @param refreshEnabled replacement refresh enabled flag
    /// @param enabledItemCount replacement enabled prefix size
    /// @return replacement snapshot
    static ResourcePackCatalogSnapshot copy(
            ResourcePackCatalogSnapshot ignoredPrevious,
            OptionalInt selectedIndex,
            OptionalInt itemCount,
            long contentRevision,
            ResourcePackCatalogStatus status,
            String statusText,
            ResourcePackCatalogWriteStatus writeStatus,
            String writeStatusText,
            boolean listEnabled,
            boolean refreshEnabled,
            int enabledItemCount) {
        Objects.requireNonNull(ignoredPrevious, "ignoredPrevious");
        return new ResourcePackCatalogSnapshot(
                selectedIndex,
                itemCount,
                contentRevision,
                status,
                statusText,
                writeStatus,
                writeStatusText,
                listEnabled,
                refreshEnabled,
                enabledItemCount);
    }
}
