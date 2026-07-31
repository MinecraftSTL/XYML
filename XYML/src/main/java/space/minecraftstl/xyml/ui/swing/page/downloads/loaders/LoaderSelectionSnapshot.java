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
package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.RemoteVersion;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// Immutable installation-facing loader selection emitted by the Swing wizard.
///
/// The list retains each concrete Core [RemoteVersion] instance in dependency-safe installation
/// order. It is deliberately not converted to display values, so the later game-install request can
/// hand the same objects to [space.minecraftstl.xyml.download.GameBuilder] without another source query.
///
/// @param gameVersion selected Minecraft version, or empty before selection
/// @param selectedRemoteVersions immutable exact Core loader versions in safe installation order
/// @param summary concise localized user-visible selection summary
@NotNullByDefault
public record LoaderSelectionSnapshot(
        Optional<String> gameVersion,
        @Unmodifiable List<RemoteVersion> selectedRemoteVersions,
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
