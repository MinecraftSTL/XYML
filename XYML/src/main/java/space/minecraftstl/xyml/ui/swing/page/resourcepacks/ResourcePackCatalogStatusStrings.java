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

/// Localized lifecycle text used by [DefaultResourcePackCatalogModel].
///
/// @param idleStatus status before lazy loading starts
/// @param loadingStatus status while one disk-scan generation is active
/// @param readyStatus status when visible packs are available
/// @param emptyStatus status when a successful scan has no visible packs
/// @param unsupportedStatus status when the Minecraft version cannot use resource packs
/// @param failedStatus prefix after the latest disk scan fails
/// @param unknownFailure fallback detail when a failure has no readable message
/// @param writeBusyStatus status while one local mutation and follow-up scan are active
/// @param writeFailedStatus prefix after the latest local mutation fails
@NotNullByDefault
public record ResourcePackCatalogStatusStrings(
        String idleStatus,
        String loadingStatus,
        String readyStatus,
        String emptyStatus,
        String unsupportedStatus,
        String failedStatus,
        String unknownFailure,
        String writeBusyStatus,
        String writeFailedStatus) {
    /// Validates every localized lifecycle value.
    public ResourcePackCatalogStatusStrings {
        Objects.requireNonNull(idleStatus, "idleStatus");
        Objects.requireNonNull(loadingStatus, "loadingStatus");
        Objects.requireNonNull(readyStatus, "readyStatus");
        Objects.requireNonNull(emptyStatus, "emptyStatus");
        Objects.requireNonNull(unsupportedStatus, "unsupportedStatus");
        Objects.requireNonNull(failedStatus, "failedStatus");
        Objects.requireNonNull(unknownFailure, "unknownFailure");
        Objects.requireNonNull(writeBusyStatus, "writeBusyStatus");
        Objects.requireNonNull(writeFailedStatus, "writeFailedStatus");
    }
}
