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
package space.minecraftstl.xyml.ui.swing.page.downloads;

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

/// In-memory immutable remote-result page exposed through the shared sparse viewport-list protocol.
///
/// The owning panel fetches one API page in the background only after an explicit user command.
/// This source then materializes only visible rows from that retained page, preserving the same
/// lazy rendering behavior used by other Swing catalog surfaces.
@NotNullByDefault
final class RemoteModpackViewportDataSource implements ViewportChoiceDataSource<RemoteModpackCatalogItem> {
    /// Lock pairing the immutable result snapshot and its source revision.
    private final Object stateLock = new Object();

    /// Latest immutable catalog page retained for sparse viewport row loading.
    private @Unmodifiable List<RemoteModpackCatalogItem> items = List.of();

    /// Monotonic identity that invalidates list completions after a search or page transition.
    private long revision;

    /// Replaces the retained remote page after a background source request completes successfully.
    ///
    /// @param updatedItems source result values to snapshot defensively
    void replaceItems(List<? extends RemoteModpackCatalogItem> updatedItems) {
        @Unmodifiable List<RemoteModpackCatalogItem> snapshot = List.copyOf(
                Objects.requireNonNull(updatedItems, "updatedItems"));
        synchronized (stateLock) {
            items = snapshot;
            revision++;
        }
    }

    /// Returns the exact count of rows in the current server result page.
    ///
    /// @return exact non-negative retained result count
    @Override
    public OptionalInt exactItemCount() {
        synchronized (stateLock) {
            return OptionalInt.of(items.size());
        }
    }

    /// Returns the content revision paired with all retained result rows.
    ///
    /// @return current source revision
    @Override
    public OptionalLong sourceRevision() {
        synchronized (stateLock) {
            return OptionalLong.of(revision);
        }
    }

    /// Returns a clamped contiguous snapshot region without issuing network work.
    ///
    /// @param desiredRange viewport-derived requested row range
    /// @param cancellation cooperative load cancellation signal
    /// @return immediately completed retained-page slice
    @Override
    public CompletionStage<ChoicePage<RemoteModpackCatalogItem>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        IndexRange requested = Objects.requireNonNull(desiredRange, "desiredRange");
        LoadCancellation signal = Objects.requireNonNull(cancellation, "cancellation");
        signal.throwIfCancelled();
        @Unmodifiable List<RemoteModpackCatalogItem> snapshot;
        synchronized (stateLock) {
            snapshot = items;
        }
        IndexRange effectiveRange = requested.clampToItemCount(snapshot.size());
        @Unmodifiable List<RemoteModpackCatalogItem> values = List.copyOf(snapshot.subList(
                effectiveRange.startInclusive(),
                effectiveRange.endExclusive()));
        return CompletableFuture.completedFuture(new ChoicePage<>(
                effectiveRange,
                values,
                OptionalInt.of(snapshot.size()),
                effectiveRange.endExclusive() == snapshot.size()));
    }
}
