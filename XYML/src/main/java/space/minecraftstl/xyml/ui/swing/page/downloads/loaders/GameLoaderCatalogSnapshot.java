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

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// Immutable state of the selected game version, loader kind, and explicitly loaded concrete versions.
///
/// A snapshot itself never loads data. UI adapters may render [#availableKinds()] before a selected
/// Minecraft version, but [#items()] can become non-empty only after an explicit source refresh.
///
/// @param gameVersion selected Minecraft version, if any
/// @param loaderKind selected compatible loader catalog, if any
/// @param availableKinds immutable historical choices for the game-version state
/// @param items immutable concrete remote versions from the selected catalog
/// @param status catalog lifecycle state
/// @param failure latest refresh failure, only when status is [GameLoaderCatalogStatus#FAILED]
/// @param revision monotonic model-owned state version
@NotNullByDefault
public record GameLoaderCatalogSnapshot(
        Optional<String> gameVersion,
        Optional<GameLoaderKind> loaderKind,
        @Unmodifiable List<GameLoaderKind> availableKinds,
        @Unmodifiable List<GameLoaderCatalogItem> items,
        GameLoaderCatalogStatus status,
        Optional<Throwable> failure,
        long revision) {
    /// Defensively snapshots collection state and rejects impossible selection/status combinations.
    public GameLoaderCatalogSnapshot {
        gameVersion = Objects.requireNonNull(gameVersion, "gameVersion");
        loaderKind = Objects.requireNonNull(loaderKind, "loaderKind");
        availableKinds = List.copyOf(Objects.requireNonNull(availableKinds, "availableKinds"));
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        status = Objects.requireNonNull(status, "status");
        failure = Objects.requireNonNull(failure, "failure");
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        Set<GameLoaderKind> distinctKinds = new HashSet<>(availableKinds);
        if (distinctKinds.size() != availableKinds.size()) {
            throw new IllegalArgumentException("availableKinds must not contain duplicates");
        }
        if (status == GameLoaderCatalogStatus.AWAITING_GAME_VERSION
                && (gameVersion.isPresent() || loaderKind.isPresent() || !items.isEmpty())) {
            throw new IllegalArgumentException("awaiting game version must have no selection or items");
        }
        if (status == GameLoaderCatalogStatus.AWAITING_LOADER
                && (!gameVersion.isPresent() || loaderKind.isPresent() || !items.isEmpty())) {
            throw new IllegalArgumentException("awaiting loader requires only a game-version selection");
        }
        if ((status == GameLoaderCatalogStatus.IDLE
                || status == GameLoaderCatalogStatus.LOADING
                || status == GameLoaderCatalogStatus.READY
                || status == GameLoaderCatalogStatus.FAILED)
                && (!gameVersion.isPresent() || !loaderKind.isPresent())) {
            throw new IllegalArgumentException("catalog status requires game and loader selections");
        }
        if (status != GameLoaderCatalogStatus.FAILED && failure.isPresent()) {
            throw new IllegalArgumentException("only failed snapshots may retain a failure");
        }
        if (status == GameLoaderCatalogStatus.FAILED && failure.isEmpty()) {
            throw new IllegalArgumentException("failed snapshots must retain a failure");
        }
        if (loaderKind.isEmpty() && !items.isEmpty()) {
            throw new IllegalArgumentException("unselected loader cannot expose items");
        }
        if (loaderKind.isPresent()) {
            GameLoaderKind selectedKind = loaderKind.get();
            for (GameLoaderCatalogItem item : items) {
                if (item.kind() != selectedKind) {
                    throw new IllegalArgumentException("catalog item belongs to another loader kind");
                }
            }
        }
    }
}
