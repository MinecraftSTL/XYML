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
package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.OptionalInt;

/// Immutable visible state for one lazily indexed instance-world catalog.
///
/// @param itemCount exact direct-child directory count, or empty until an index succeeds
/// @param contentRevision revision identifying the current immutable directory index
/// @param status latest shallow-index lifecycle state
/// @param statusText localized shallow-index status text
/// @param operationText localized active or failed import/delete status, empty while idle
/// @param listEnabled whether indexed rows may currently be selected
/// @param refreshEnabled whether a fresh shallow index may be started
/// @param operationPending whether an import or delete currently owns the catalog
@NotNullByDefault
public record WorldCatalogSnapshot(
        OptionalInt itemCount,
        long contentRevision,
        WorldCatalogStatus status,
        String statusText,
        String operationText,
        boolean listEnabled,
        boolean refreshEnabled,
        boolean operationPending) {
    /// Validates one atomically published catalog state.
    public WorldCatalogSnapshot {
        Objects.requireNonNull(itemCount, "itemCount");
        Objects.requireNonNull(status, "status");
        statusText = Objects.requireNonNull(statusText, "statusText");
        operationText = Objects.requireNonNull(operationText, "operationText");
        if (contentRevision < 0L) {
            throw new IllegalArgumentException("contentRevision must not be negative");
        }
        if (status == WorldCatalogStatus.READY && itemCount.isEmpty()) {
            throw new IllegalArgumentException("Ready world catalog must have an exact item count");
        }
        if (status != WorldCatalogStatus.READY && listEnabled) {
            throw new IllegalArgumentException("Only a ready world catalog may enable its list");
        }
        if (listEnabled && itemCount.orElseThrow() == 0) {
            throw new IllegalArgumentException("An empty world catalog cannot enable its list");
        }
        if (operationPending && (listEnabled || refreshEnabled)) {
            throw new IllegalArgumentException("An active world mutation must disable list and refresh");
        }
        if (operationPending && operationText.isBlank()) {
            throw new IllegalArgumentException("An active world mutation requires operation text");
        }
    }
}
