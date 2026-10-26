// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.install;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.ComponentRemoteVersion;

import java.util.List;
import java.util.Objects;

/// Captures the user-confirmed instance name, base game-version identifier, and optional installers.
///
/// Values are validated but never trimmed, rewritten, or given generated suffixes. Remote installers
/// are snapshotted in their supplied order so a user-selected loader combination reaches the core task
/// without accidental deduplication by [ComponentRemoteVersion#equals(Object)].
///
/// @param instanceName exact new instance identifier entered or accepted by the user
/// @param versionId exact stable game-version identifier selected from the catalog
/// @param selectedRemoteVersions immutable ordered remote installers selected for this instance
@NotNullByDefault
public record GameInstallRequest(
        String instanceName,
        String versionId,
        @Unmodifiable List<ComponentRemoteVersion> selectedRemoteVersions) {
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
