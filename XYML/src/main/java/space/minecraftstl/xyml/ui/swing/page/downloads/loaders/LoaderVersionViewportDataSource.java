// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
