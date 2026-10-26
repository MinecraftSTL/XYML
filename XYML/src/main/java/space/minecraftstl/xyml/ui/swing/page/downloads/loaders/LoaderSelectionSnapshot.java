// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.ComponentRemoteVersion;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// Immutable installation-facing loader selection emitted by the Swing wizard.
///
/// The list retains each concrete Core [ComponentRemoteVersion] instance in dependency-safe installation
/// order. It is deliberately not converted to display values, so the later game-install request can
/// hand the same objects to [space.minecraftstl.xyml.download.GameBuilder] without another source query.
///
/// @param gameVersion selected Minecraft version, or empty before selection
/// @param selectedRemoteVersions immutable exact Core loader versions in safe installation order
/// @param summary concise localized user-visible selection summary
@NotNullByDefault
public record LoaderSelectionSnapshot(
        Optional<String> gameVersion,
        @Unmodifiable List<ComponentRemoteVersion> selectedRemoteVersions,
        String summary) {
    /// Defensively snapshots the selected Core instances and validates visible state.
    public LoaderSelectionSnapshot {
        gameVersion = Objects.requireNonNull(gameVersion, "gameVersion");
        selectedRemoteVersions = List.copyOf(Objects.requireNonNull(
                selectedRemoteVersions,
                "selectedRemoteVersions"));
        summary = Objects.requireNonNull(summary, "summary");
    }
}
