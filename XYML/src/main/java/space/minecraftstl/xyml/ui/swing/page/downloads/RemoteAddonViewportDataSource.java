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

/// Exposes one retained remote provider page through the shared sparse viewport-list contract.
///
/// The owning catalog performs network search separately. This source only materializes slices of
/// an already returned provider page, so list scrolling cannot trigger unexpected source requests.
@NotNullByDefault
final class RemoteAddonViewportDataSource implements ViewportChoiceDataSource<RemoteAddonCatalogItem> {
    /// Lock pairing the immutable retained rows with their source revision.
    private final Object stateLock = new Object();

    /// Latest immutable provider page, initially empty before a successful explicit search.
    private @Unmodifiable List<RemoteAddonCatalogItem> items = List.of();

    /// Monotonic source identity invalidating stale sparse-list completions after any page replacement.
    private long revision;

    /// Replaces the retained rows after a current explicit source request completes.
    ///
    /// @param updatedItems provider rows to snapshot defensively
    void replaceItems(List<? extends RemoteAddonCatalogItem> updatedItems) {
        @Unmodifiable List<RemoteAddonCatalogItem> snapshot = List.copyOf(
                Objects.requireNonNull(updatedItems, "updatedItems"));
        synchronized (stateLock) {
            items = snapshot;
            revision++;
        }
    }

    /// Returns the exact number of rows in the retained provider page.
    ///
    /// @return non-negative retained row count
    @Override
    public OptionalInt exactItemCount() {
        synchronized (stateLock) {
            return OptionalInt.of(items.size());
        }
    }

    /// Returns the identity paired with all currently retained rows.
    ///
    /// @return current monotonic source revision
    @Override
    public OptionalLong sourceRevision() {
        synchronized (stateLock) {
            return OptionalLong.of(revision);
        }
    }

    /// Returns a clamped immutable row slice without source I/O.
    ///
    /// @param desiredRange viewport-demanded logical row range
    /// @param cancellation cooperative sparse-load cancellation signal
    /// @return immediately completed retained-page slice
    @Override
    public CompletionStage<ChoicePage<RemoteAddonCatalogItem>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        IndexRange requested = Objects.requireNonNull(desiredRange, "desiredRange");
        LoadCancellation signal = Objects.requireNonNull(cancellation, "cancellation");
        signal.throwIfCancelled();
        @Unmodifiable List<RemoteAddonCatalogItem> snapshot;
        synchronized (stateLock) {
            snapshot = items;
        }
        IndexRange effectiveRange = requested.clampToItemCount(snapshot.size());
        @Unmodifiable List<RemoteAddonCatalogItem> values = List.copyOf(snapshot.subList(
                effectiveRange.startInclusive(),
                effectiveRange.endExclusive()));
        return CompletableFuture.completedFuture(new ChoicePage<>(
                effectiveRange,
                values,
                OptionalInt.of(snapshot.size()),
                effectiveRange.endExclusive() == snapshot.size()));
    }
}
