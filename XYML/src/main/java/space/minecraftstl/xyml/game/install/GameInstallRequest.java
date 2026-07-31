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
package space.minecraftstl.xyml.game.install;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.RemoteVersion;

import java.util.List;
import java.util.Objects;

/// Captures the user-confirmed instance name, base game-version identifier, and optional installers.
///
/// Values are validated but never trimmed, rewritten, or given generated suffixes. Remote installers
/// are snapshotted in their supplied order so a user-selected loader combination reaches the core task
/// without accidental deduplication by [RemoteVersion#equals(Object)].
///
/// @param instanceName exact new instance identifier entered or accepted by the user
/// @param versionId exact stable game-version identifier selected from the catalog
/// @param selectedRemoteVersions immutable ordered remote installers selected for this instance
@NotNullByDefault
public record GameInstallRequest(
        String instanceName,
        String versionId,
        @Unmodifiable List<RemoteVersion> selectedRemoteVersions) {
    /// Rejects missing or blank text and snapshots the selected installer order.
    public GameInstallRequest {
        requireText(instanceName, "instanceName");
        requireText(versionId, "versionId");
        selectedRemoteVersions = List.copyOf(Objects.requireNonNull(
                selectedRemoteVersions,
                "selectedRemoteVersions"));
    }

    /// Creates a vanilla-only request for callers that do not select a loader.
    ///
    /// @param instanceName exact new instance identifier entered or accepted by the user
    /// @param versionId exact stable game-version identifier selected from the catalog
    public GameInstallRequest(String instanceName, String versionId) {
        this(instanceName, versionId, List.of());
    }

    /// Validates one required request value without normalizing it.
    ///
    /// @param value request value
    /// @param name component name used in diagnostics
    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
