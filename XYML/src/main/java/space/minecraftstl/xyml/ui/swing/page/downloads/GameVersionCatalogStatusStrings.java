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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localizable lifecycle text used by [DefaultGameVersionCatalogModel].
///
/// @param idleStatus status before lazy loading starts
/// @param loadingStatus status while one source generation is active
/// @param readyStatus status when visible results are available
/// @param emptyStatus status when a successful catalog has no visible results
/// @param failedStatus status after the latest source generation fails
@NotNullByDefault
public record GameVersionCatalogStatusStrings(
        String idleStatus,
        String loadingStatus,
        String readyStatus,
        String emptyStatus,
        String failedStatus) {
    /// Validates localized status text.
    public GameVersionCatalogStatusStrings {
        Objects.requireNonNull(idleStatus, "idleStatus");
        Objects.requireNonNull(loadingStatus, "loadingStatus");
        Objects.requireNonNull(readyStatus, "readyStatus");
        Objects.requireNonNull(emptyStatus, "emptyStatus");
        Objects.requireNonNull(failedStatus, "failedStatus");
    }
}
