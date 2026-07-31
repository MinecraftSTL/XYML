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
package space.minecraftstl.xyml.ui.swing.update;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.upgrade.RemoteVersion;

import java.time.Instant;
import java.util.Objects;

/// Immutable successful result of one remote launcher update check.
///
/// @param request exact request that produced the result
/// @param remoteVersion latest version returned by the update service
/// @param updateAvailable whether policy considers the remote version newer or otherwise required
/// @param checkedAt completion time of the successful check
@NotNullByDefault
public record UpdateCheckResult(
        UpdateCheckRequest request,
        RemoteVersion remoteVersion,
        boolean updateAvailable,
        Instant checkedAt) {
    /// Validates one successful update result.
    public UpdateCheckResult {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(remoteVersion, "remoteVersion");
        Objects.requireNonNull(checkedAt, "checkedAt");
    }
}
