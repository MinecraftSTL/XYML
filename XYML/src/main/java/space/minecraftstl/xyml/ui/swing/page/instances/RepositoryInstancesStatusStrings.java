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

/// Localizable repository status and fallback text used by [RepositoryInstancesModel].
///
/// @param loadingStatus initial repository-loading status
/// @param readyStatus repository-ready status
/// @param refreshingStatus active refresh status
/// @param refreshFailedStatus refresh failure status
/// @param unknownVersionDetail fallback detail when a game version cannot be identified
@NotNullByDefault
public record RepositoryInstancesStatusStrings(
        String loadingStatus,
        String readyStatus,
        String refreshingStatus,
        String refreshFailedStatus,
        String unknownVersionDetail) {
    /// Validates localized repository text.
    public RepositoryInstancesStatusStrings {
        Objects.requireNonNull(loadingStatus, "loadingStatus");
        Objects.requireNonNull(readyStatus, "readyStatus");
        Objects.requireNonNull(refreshingStatus, "refreshingStatus");
        Objects.requireNonNull(refreshFailedStatus, "refreshFailedStatus");
        Objects.requireNonNull(unknownVersionDetail, "unknownVersionDetail");
    }
}
