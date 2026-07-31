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
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceDataSource;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/// Exposes one explicitly refreshed loader catalog through the shared viewport-driven list contract.
///
/// Replacing this data source never contacts a remote provider. The owner performs the explicit
/// catalog refresh first, then this source supplies only the actually visible row slice to the
/// reusable Swing renderer.
@NotNullByDefault
final class LoaderVersionViewportDataSource implements ViewportChoiceDataSource<GameLoaderCatalogItem> {
    /// Lock pairing immutable version rows with their change revision.
    private final Object stateLock = new Object();

    /// Latest explicitly loaded immutable catalog rows, initially empty.
    private @Unmodifiable List<GameLoaderCatalogItem> items = List.of();

    /// Monotonic content identity used to reject stale sparse-list completions.
    private long revision;

    /// Replaces visible rows with an immutable exact catalog snapshot.
    ///
    /// @param updatedItems explicit selected-loader catalog rows
    void replaceItems(List<? extends GameLoaderCatalogItem> updatedItems) {
        @Unmodifiable List<GameLoaderCatalogItem> snapshot = List.copyOf(Objects.requireNonNull(
                updatedItems,
                "updatedItems"));
        synchronized (stateLock) {
            items = snapshot;
            revision++;
        }
    }

    /// Returns the exact number of rows in the last explicit catalog response.
    ///
    /// @return exact local catalog row count
    @Override
    public OptionalInt exactItemCount() {
        synchronized (stateLock) {
            return OptionalInt.of(items.size());
        }
    }

    /// Returns the identity paired with the current immutable row snapshot.
    ///
    /// @return current content revision
    @Override
    public OptionalLong sourceRevision() {
        synchronized (stateLock) {
            return OptionalLong.of(revision);
        }
    }

    /// Returns only the viewport-demanded local slice without starting source work.
    ///
    /// @param desiredRange measured viewport range
    /// @param cancellation cooperative list-load cancellation signal
    /// @return immediately completed row page from the retained catalog snapshot
    @Override
    public CompletionStage<ChoicePage<GameLoaderCatalogItem>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        IndexRange requestedRange = Objects.requireNonNull(desiredRange, "desiredRange");
        LoadCancellation cancellationSignal = Objects.requireNonNull(cancellation, "cancellation");
        cancellationSignal.throwIfCancelled();
        @Unmodifiable List<GameLoaderCatalogItem> snapshot;
        synchronized (stateLock) {
            snapshot = items;
        }
        IndexRange effectiveRange = requestedRange.clampToItemCount(snapshot.size());
        @Unmodifiable List<GameLoaderCatalogItem> values = List.copyOf(snapshot.subList(
                effectiveRange.startInclusive(),
                effectiveRange.endExclusive()));
        return CompletableFuture.completedFuture(new ChoicePage<>(
                effectiveRange,
                values,
                OptionalInt.of(snapshot.size()),
                effectiveRange.endExclusive() == snapshot.size()));
    }
}
