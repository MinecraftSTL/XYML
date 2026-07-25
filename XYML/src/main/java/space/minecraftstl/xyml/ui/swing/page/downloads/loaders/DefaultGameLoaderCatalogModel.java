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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/// Owns the explicit game-version and loader selection needed before a loader catalog can refresh.
///
/// Construction and selection only produce immutable local snapshots. The model calls its source
/// only from [#refreshAsync()], which prevents opening a wizard or changing the base game version
/// from loading every loader list over the network.
@NotNullByDefault
public final class DefaultGameLoaderCatalogModel implements AutoCloseable {
    /// Lock guarding generation, lifecycle, and every snapshot replacement.
    private final Object stateLock = new Object();

    /// Source reached only after an explicit selected-catalog refresh.
    private final GameLoaderCatalogSource source;

    /// Latest immutable state, safely readable without taking the state lock.
    private volatile GameLoaderCatalogSnapshot snapshot;

    /// Monotonic request generation used to discard stale asynchronous results.
    private long requestGeneration;

    /// Whether no further selection or refresh operation is permitted.
    private boolean closed;

    /// Creates a local idle model without consulting the source.
    ///
    /// @param source source invoked only by [#refreshAsync()]
    public DefaultGameLoaderCatalogModel(GameLoaderCatalogSource source) {
        this.source = Objects.requireNonNull(source, "source");
        snapshot = new GameLoaderCatalogSnapshot(
                Optional.empty(),
                Optional.empty(),
                GameLoaderCompatibilityMatrix.kindsForGameVersion(null),
                List.of(),
                GameLoaderCatalogStatus.AWAITING_GAME_VERSION,
                Optional.empty(),
                0L);
    }

    /// Returns the latest immutable model state.
    ///
    /// @return current catalog snapshot
    public GameLoaderCatalogSnapshot snapshot() {
        return snapshot;
    }

    /// Selects a base Minecraft version without contacting any remote source.
    ///
    /// Any previous loader selection and materialized versions are discarded because they belong to
    /// the old game-version context.
    ///
    /// @param gameVersion selected non-blank Minecraft version
    public void selectGameVersion(String gameVersion) {
        String normalizedGameVersion = normalizeGameVersion(gameVersion);
        synchronized (stateLock) {
            requireOpen();
            requestGeneration++;
            snapshot = new GameLoaderCatalogSnapshot(
                    Optional.of(normalizedGameVersion),
                    Optional.empty(),
                    GameLoaderCompatibilityMatrix.kindsForGameVersion(normalizedGameVersion),
                    List.of(),
                    GameLoaderCatalogStatus.AWAITING_LOADER,
                    Optional.empty(),
                    nextRevisionLocked());
        }
    }

    /// Clears the base Minecraft version and any dependent loader selection without contacting a source.
    public void clearGameVersion() {
        synchronized (stateLock) {
            requireOpen();
            requestGeneration++;
            snapshot = new GameLoaderCatalogSnapshot(
                    Optional.empty(),
                    Optional.empty(),
                    GameLoaderCompatibilityMatrix.kindsForGameVersion(null),
                    List.of(),
                    GameLoaderCatalogStatus.AWAITING_GAME_VERSION,
                    Optional.empty(),
                    nextRevisionLocked());
        }
    }

    /// Selects one compatible loader catalog without refreshing it.
    ///
    /// @param kind compatible kind from the currently selected game-version snapshot
    /// @throws IllegalStateException when no base game version is selected
    /// @throws IllegalArgumentException when the kind is unavailable for the selected game version
    public void selectLoaderKind(GameLoaderKind kind) {
        GameLoaderKind selectedKind = Objects.requireNonNull(kind, "kind");
        synchronized (stateLock) {
            requireOpen();
            GameLoaderCatalogSnapshot current = snapshot;
            String gameVersion = current.gameVersion().orElseThrow(
                    () -> new IllegalStateException("Select a Minecraft version before selecting a loader"));
            if (!current.availableKinds().contains(selectedKind)) {
                throw new IllegalArgumentException(
                        "Loader %s is unavailable for Minecraft %s".formatted(selectedKind, gameVersion));
            }
            requestGeneration++;
            snapshot = new GameLoaderCatalogSnapshot(
                    Optional.of(gameVersion),
                    Optional.of(selectedKind),
                    current.availableKinds(),
                    List.of(),
                    GameLoaderCatalogStatus.IDLE,
                    Optional.empty(),
                    nextRevisionLocked());
        }
    }

    /// Starts one source refresh only after both required selections exist.
    ///
    /// The returned stage always resolves to the newest applicable snapshot. Source failures become
    /// [GameLoaderCatalogStatus#FAILED] snapshots so a UI can render the failure without requiring
    /// a toolkit-specific exceptional-completion path.
    ///
    /// @return eventual terminal or newer superseding snapshot
    /// @throws IllegalStateException when the game version or loader kind is not selected
    public CompletionStage<GameLoaderCatalogSnapshot> refreshAsync() {
        GameLoaderCatalogRequest request;
        long generation;
        CompletableFuture<GameLoaderCatalogSnapshot> completion = new CompletableFuture<>();
        synchronized (stateLock) {
            requireOpen();
            GameLoaderCatalogSnapshot current = snapshot;
            String gameVersion = current.gameVersion().orElseThrow(
                    () -> new IllegalStateException("Select a Minecraft version before refreshing loaders"));
            GameLoaderKind kind = current.loaderKind().orElseThrow(
                    () -> new IllegalStateException("Select a loader before refreshing its catalog"));
            generation = ++requestGeneration;
            request = new GameLoaderCatalogRequest(gameVersion, kind);
            snapshot = new GameLoaderCatalogSnapshot(
                    Optional.of(gameVersion),
                    Optional.of(kind),
                    current.availableKinds(),
                    List.of(),
                    GameLoaderCatalogStatus.LOADING,
                    Optional.empty(),
                    nextRevisionLocked());
        }

        final CompletionStage<@Unmodifiable List<GameLoaderCatalogItem>> refreshStage;
        try {
            refreshStage = Objects.requireNonNull(
                    source.refreshAsync(request),
                    "loader catalog source returned null stage");
        } catch (RuntimeException sourceFailure) {
            completeRefresh(generation, request, null, sourceFailure, completion);
            return completion.minimalCompletionStage();
        } catch (Error sourceError) {
            completeRefresh(generation, request, null, sourceError, completion);
            throw sourceError;
        }

        try {
            refreshStage.whenComplete((items, failure) ->
                    completeRefresh(generation, request, items, failure, completion));
        } catch (RuntimeException registrationFailure) {
            completeRefresh(generation, request, null, registrationFailure, completion);
        } catch (Error registrationError) {
            completeRefresh(generation, request, null, registrationError, completion);
            throw registrationError;
        }
        return completion.minimalCompletionStage();
    }

    /// Prevents late source completions from replacing the retained state.
    ///
    /// The caller owns the source lifecycle, so closing this model does not cancel or close a
    /// shared source instance.
    @Override
    public void close() {
        synchronized (stateLock) {
            if (!closed) {
                closed = true;
                requestGeneration++;
            }
        }
    }

    /// Commits an applicable source result or a failure snapshot and resolves the caller stage.
    ///
    /// @param generation source generation that completed
    /// @param request request that started the source operation
    /// @param items returned source items, or null after a failed operation
    /// @param failure source failure, or null after apparent success
    /// @param completion externally visible result completion
    private void completeRefresh(
            long generation,
            GameLoaderCatalogRequest request,
            @Nullable @Unmodifiable List<GameLoaderCatalogItem> items,
            @Nullable Throwable failure,
            CompletableFuture<GameLoaderCatalogSnapshot> completion) {
        @Nullable Throwable resolvedFailure = failure;
        @Unmodifiable List<GameLoaderCatalogItem> immutableItems = List.of();
        if (resolvedFailure == null) {
            try {
                immutableItems = validateItems(request, Objects.requireNonNull(
                        items,
                        "loader catalog source returned null items"));
            } catch (RuntimeException validationFailure) {
                resolvedFailure = validationFailure;
            }
        }

        GameLoaderCatalogSnapshot completedSnapshot;
        synchronized (stateLock) {
            if (closed || generation != requestGeneration) {
                completedSnapshot = snapshot;
            } else if (resolvedFailure == null) {
                GameLoaderCatalogSnapshot current = snapshot;
                completedSnapshot = new GameLoaderCatalogSnapshot(
                        Optional.of(request.gameVersion()),
                        Optional.of(request.kind()),
                        current.availableKinds(),
                        immutableItems,
                        GameLoaderCatalogStatus.READY,
                        Optional.empty(),
                        nextRevisionLocked());
                snapshot = completedSnapshot;
            } else {
                GameLoaderCatalogSnapshot current = snapshot;
                completedSnapshot = new GameLoaderCatalogSnapshot(
                        Optional.of(request.gameVersion()),
                        Optional.of(request.kind()),
                        current.availableKinds(),
                        List.of(),
                        GameLoaderCatalogStatus.FAILED,
                        Optional.of(resolvedFailure),
                        nextRevisionLocked());
                snapshot = completedSnapshot;
            }
        }
        completion.complete(completedSnapshot);
    }

    /// Validates and defensively snapshots items for their exact selected catalog request.
    ///
    /// @param request selected catalog request
    /// @param items apparent source result
    /// @return immutable items retaining their original RemoteVersion instances
    private static @Unmodifiable List<GameLoaderCatalogItem> validateItems(
            GameLoaderCatalogRequest request,
            List<GameLoaderCatalogItem> items) {
        List<GameLoaderCatalogItem> copy = List.copyOf(Objects.requireNonNull(items, "items"));
        for (GameLoaderCatalogItem item : copy) {
            if (item.kind() != request.kind()) {
                throw new IllegalStateException("Loader catalog source returned an item for another kind");
            }
            if (!request.gameVersion().equals(item.remoteVersion().getGameVersion())) {
                throw new IllegalStateException("Loader catalog source returned an item for another game version");
            }
        }
        return copy;
    }

    /// Normalizes a selected Minecraft version without consulting a remote source.
    ///
    /// @param gameVersion raw selected version
    /// @return non-blank normalized version
    private static String normalizeGameVersion(String gameVersion) {
        String normalizedGameVersion = Objects.requireNonNull(gameVersion, "gameVersion").trim();
        if (normalizedGameVersion.isEmpty()) {
            throw new IllegalArgumentException("gameVersion must not be blank");
        }
        return normalizedGameVersion;
    }

    /// Returns the revision for the state replacement currently protected by the state lock.
    ///
    /// @return next monotonic snapshot revision
    private long nextRevisionLocked() {
        return snapshot.revision() + 1L;
    }

    /// Rejects operations after close.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Game-loader catalog model is closed");
        }
    }
}
