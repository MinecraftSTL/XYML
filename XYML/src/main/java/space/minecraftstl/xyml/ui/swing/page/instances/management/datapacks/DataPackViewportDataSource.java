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
package space.minecraftstl.xyml.ui.swing.page.instances.management.datapacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.datapack.DataPack;
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

/// In-memory immutable data-pack snapshot exposed through the shared sparse viewport-list protocol.
///
/// `DataPack` discovery happens off the EDT in the owning panel. This source then lets the visual
/// list materialize only the rows that its measured viewport needs, rather than creating one Swing
/// renderer value for every installed pack at selection time.
@NotNullByDefault
final class DataPackViewportDataSource implements ViewportChoiceDataSource<DataPack.Pack> {
    /// Lock pairing a complete immutable data-pack snapshot with its source revision.
    private final Object stateLock = new Object();

    /// Latest immutable snapshot produced by the selected world's DataPack API.
    private @Unmodifiable List<DataPack.Pack> packs = List.of();

    /// Monotonic content identity used to discard old viewport completions after reselection.
    private long revision;

    /// Replaces the exact data-pack snapshot after background discovery or mutation completes.
    ///
    /// @param updatedPacks immutable or mutable data-pack values to snapshot defensively
    void replacePacks(List<? extends DataPack.Pack> updatedPacks) {
        @Unmodifiable List<DataPack.Pack> snapshot = List.copyOf(Objects.requireNonNull(updatedPacks, "updatedPacks"));
        synchronized (stateLock) {
            packs = snapshot;
            revision++;
        }
    }

    /// Returns the exact count from the latest selected-world snapshot.
    ///
    /// @return exact non-negative data-pack count
    @Override
    public OptionalInt exactItemCount() {
        synchronized (stateLock) {
            return OptionalInt.of(packs.size());
        }
    }

    /// Returns the snapshot revision paired with all list values.
    ///
    /// @return source revision used by the sparse list to reject stale rows
    @Override
    public OptionalLong sourceRevision() {
        synchronized (stateLock) {
            return OptionalLong.of(revision);
        }
    }

    /// Returns a contiguous portion of the current immutable snapshot without background work.
    ///
    /// @param desiredRange viewport-derived requested range
    /// @param cancellation cooperative cancellation signal
    /// @return immediately completed page matching the current selected-world snapshot
    @Override
    public CompletionStage<ChoicePage<DataPack.Pack>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        IndexRange requested = Objects.requireNonNull(desiredRange, "desiredRange");
        LoadCancellation signal = Objects.requireNonNull(cancellation, "cancellation");
        signal.throwIfCancelled();
        @Unmodifiable List<DataPack.Pack> snapshot;
        synchronized (stateLock) {
            snapshot = packs;
        }
        IndexRange effectiveRange = requested.clampToItemCount(snapshot.size());
        @Unmodifiable List<DataPack.Pack> values = List.copyOf(snapshot.subList(
                effectiveRange.startInclusive(),
                effectiveRange.endExclusive()));
        ChoicePage<DataPack.Pack> page = new ChoicePage<>(
                effectiveRange,
                values,
                OptionalInt.of(snapshot.size()),
                effectiveRange.endExclusive() == snapshot.size());
        return CompletableFuture.completedFuture(page);
    }
}
